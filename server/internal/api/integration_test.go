package api_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/MasterWWW/DailyPlan/server/internal/api"
	"github.com/MasterWWW/DailyPlan/server/internal/config"
	"github.com/MasterWWW/DailyPlan/server/internal/model"
	"github.com/MasterWWW/DailyPlan/server/internal/storage"
	mysqlstore "github.com/MasterWWW/DailyPlan/server/internal/store/mysql"
)

const testToken = "test-token"

type apiHarness struct {
	url     string
	store   *mysqlstore.Store
	storage *storage.Store
	client  *http.Client
}

func newAPIHarness(t *testing.T, readOnly bool) *apiHarness {
	t.Helper()
	dsn := os.Getenv("DAILYPLAN_TEST_MYSQL_DSN")
	if dsn == "" {
		t.Skip("set DAILYPLAN_TEST_MYSQL_DSN to run HTTP integration tests")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(cancel)
	dataStore, err := mysqlstore.Open(
		ctx,
		dsn,
		fmt.Sprintf("api-test-%d", time.Now().UnixNano()),
	)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = dataStore.Close() })
	attachments, err := storage.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	handler := api.New(
		dataStore,
		attachments,
		config.Config{
			APIToken:       testToken,
			ReadOnly:       readOnly,
			MaxUploadBytes: 1 << 20,
		},
		log.New(io.Discard, "", 0),
		false,
	)
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	return &apiHarness{
		url:     server.URL,
		store:   dataStore,
		storage: attachments,
		client:  server.Client(),
	}
}

func (h *apiHarness) request(
	t *testing.T,
	method, path string,
	body []byte,
	headers map[string]string,
) (*http.Response, []byte) {
	t.Helper()
	request, err := http.NewRequest(method, h.url+path, bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+testToken)
	for key, value := range headers {
		request.Header.Set(key, value)
	}
	response, err := h.client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	content, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	return response, content
}

func TestHTTPDevicesDeduplicateAndPullOneChange(t *testing.T) {
	harness := newAPIHarness(t, false)

	unauthorized, err := http.Get(harness.url + "/v1/status")
	if err != nil {
		t.Fatal(err)
	}
	unauthorized.Body.Close()
	if unauthorized.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthorized status = %d, want 401", unauthorized.StatusCode)
	}

	body := createOperationBody(t, "dev-a", "op-create-1")
	response, first := harness.request(t, http.MethodPost, "/v1/operations", body, nil)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("create status = %d: %s", response.StatusCode, first)
	}
	var receipt model.OperationReceipt
	if err := json.Unmarshal(first, &receipt); err != nil {
		t.Fatal(err)
	}
	if receipt.Cursor != 1 || receipt.Task == nil || receipt.Task.Title != "交方案" {
		t.Fatalf("unexpected create receipt: %#v", receipt)
	}

	response, duplicate := harness.request(t, http.MethodPost, "/v1/operations", body, nil)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("duplicate status = %d: %s", response.StatusCode, duplicate)
	}
	var repeated model.OperationReceipt
	if err := json.Unmarshal(duplicate, &repeated); err != nil {
		t.Fatal(err)
	}
	if repeated.Cursor != 1 {
		t.Fatalf("duplicate cursor = %d, want 1", repeated.Cursor)
	}

	response, changesBody := harness.request(t, http.MethodGet, "/v1/changes?after=0&limit=100", nil, nil)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("changes status = %d: %s", response.StatusCode, changesBody)
	}
	var changes model.ChangesPage
	if err := json.Unmarshal(changesBody, &changes); err != nil {
		t.Fatal(err)
	}
	if len(changes.Changes) != 1 || changes.NextCursor != 1 || changes.HasMore {
		t.Fatalf("unexpected changes page: %#v", changes)
	}

	response, snapshotBody := harness.request(t, http.MethodGet, "/v1/snapshot", nil, nil)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("snapshot status = %d: %s", response.StatusCode, snapshotBody)
	}
	var snapshot model.Snapshot
	if err := json.Unmarshal(snapshotBody, &snapshot); err != nil {
		t.Fatal(err)
	}
	if snapshot.Cursor != 1 || len(snapshot.Tasks) != 1 {
		t.Fatalf("unexpected snapshot: %#v", snapshot)
	}
}

func TestHTTPAttachmentUploadAndRangeDownload(t *testing.T) {
	harness := newAPIHarness(t, false)
	body := createOperationBody(t, "dev-a", "op-attachment-task")
	response, content := harness.request(t, http.MethodPost, "/v1/operations", body, nil)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("create task status = %d: %s", response.StatusCode, content)
	}

	wav := []byte("RIFF\x24\x00\x00\x00WAVEfmt ")
	digest := sha256.Sum256(wav)
	path := "/v1/attachments/recording-http?taskId=task-http&kind=recording&mediaType=audio%2Fwav"
	response, content = harness.request(t, http.MethodPut, path, wav, map[string]string{
		"Content-Type":     "audio/wav",
		"X-Content-SHA256": hex.EncodeToString(digest[:]),
		"Content-Length":   fmt.Sprintf("%d", len(wav)),
	})
	if response.StatusCode != http.StatusCreated {
		t.Fatalf("upload status = %d: %s", response.StatusCode, content)
	}
	var attachment model.Attachment
	if err := json.Unmarshal(content, &attachment); err != nil {
		t.Fatal(err)
	}
	if attachment.TranscriptionStatus == nil || *attachment.TranscriptionStatus != model.TranscriptionPending {
		t.Fatalf("unexpected attachment: %#v", attachment)
	}

	response, downloaded := harness.request(
		t,
		http.MethodGet,
		"/v1/attachments/recording-http",
		nil,
		map[string]string{"Range": "bytes=0-3"},
	)
	if response.StatusCode != http.StatusPartialContent {
		t.Fatalf("range status = %d: %s", response.StatusCode, downloaded)
	}
	if string(downloaded) != "RIFF" {
		t.Fatalf("range body = %q, want RIFF", downloaded)
	}
}

func TestHTTPAttachmentReuploadRepairsMissingFile(t *testing.T) {
	harness := newAPIHarness(t, false)
	body := createOperationBody(t, "dev-a", "op-repair-attachment-task")
	response, content := harness.request(t, http.MethodPost, "/v1/operations", body, nil)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("create task status = %d: %s", response.StatusCode, content)
	}

	image := []byte("\x89PNG\r\n\x1a\nrepaired")
	digest := sha256.Sum256(image)
	path := "/v1/attachments/screenshot-repair?taskId=task-http&kind=screenshot&mediaType=image%2Fpng"
	headers := map[string]string{
		"Content-Type":     "image/png",
		"X-Content-SHA256": hex.EncodeToString(digest[:]),
		"Content-Length":   fmt.Sprintf("%d", len(image)),
	}
	response, content = harness.request(t, http.MethodPut, path, image, headers)
	if response.StatusCode != http.StatusCreated {
		t.Fatalf("first upload status = %d: %s", response.StatusCode, content)
	}
	var attachment model.Attachment
	if err := json.Unmarshal(content, &attachment); err != nil {
		t.Fatal(err)
	}
	harness.storage.Remove(attachment.StorageKey)

	response, content = harness.request(t, http.MethodPut, path, image, headers)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("repair upload status = %d: %s", response.StatusCode, content)
	}
	response, downloaded := harness.request(t, http.MethodGet, "/v1/attachments/screenshot-repair", nil, nil)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("download repaired status = %d: %s", response.StatusCode, downloaded)
	}
	if !bytes.Equal(downloaded, image) {
		t.Fatalf("repaired content differs: %q", downloaded)
	}
}

func TestHTTPConcurrentAttachmentUploadKeepsOnlyWinner(t *testing.T) {
	harness := newAPIHarness(t, false)
	body := createOperationBody(t, "dev-a", "op-concurrent-attachment-task")
	response, content := harness.request(t, http.MethodPost, "/v1/operations", body, nil)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("create task status = %d: %s", response.StatusCode, content)
	}

	path := "/v1/attachments/screenshot-race?taskId=task-http&kind=screenshot&mediaType=image%2Fpng"
	contents := [][]byte{
		[]byte("\x89PNG\r\n\x1a\nfirst"),
		[]byte("\x89PNG\r\n\x1a\nsecond"),
	}
	type result struct {
		status int
		body   []byte
	}
	results := make([]result, len(contents))
	var wait sync.WaitGroup
	for index, image := range contents {
		wait.Add(1)
		go func(index int, image []byte) {
			defer wait.Done()
			digest := sha256.Sum256(image)
			request, err := http.NewRequest(http.MethodPut, harness.url+path, bytes.NewReader(image))
			if err != nil {
				results[index] = result{status: -1, body: []byte(err.Error())}
				return
			}
			request.Header.Set("Authorization", "Bearer "+testToken)
			request.Header.Set("Content-Type", "image/png")
			request.Header.Set("X-Content-SHA256", hex.EncodeToString(digest[:]))
			response, err := harness.client.Do(request)
			if err != nil {
				results[index] = result{status: -1, body: []byte(err.Error())}
				return
			}
			defer response.Body.Close()
			responseBody, err := io.ReadAll(response.Body)
			if err != nil {
				results[index] = result{status: -1, body: []byte(err.Error())}
				return
			}
			results[index] = result{status: response.StatusCode, body: responseBody}
		}(index, image)
	}
	wait.Wait()

	successes := 0
	var winner []byte
	for index, result := range results {
		switch result.status {
		case http.StatusCreated:
			successes++
			winner = contents[index]
		case http.StatusConflict:
		default:
			t.Fatalf("concurrent upload %d returned %d: %s", index, result.status, result.body)
		}
	}
	if successes != 1 {
		t.Fatalf("successful uploads = %d, want 1: %#v", successes, results)
	}
	response, downloaded := harness.request(t, http.MethodGet, "/v1/attachments/screenshot-race", nil, nil)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("download winner status = %d: %s", response.StatusCode, downloaded)
	}
	if !bytes.Equal(downloaded, winner) {
		t.Fatalf("downloaded content = %q, want %q", downloaded, winner)
	}
}

func TestHTTPReadOnlyBlocksWrites(t *testing.T) {
	harness := newAPIHarness(t, true)
	body := createOperationBody(t, "dev-a", "op-read-only")
	response, content := harness.request(t, http.MethodPost, "/v1/operations", body, nil)
	if response.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("read-only status = %d, want 503: %s", response.StatusCode, content)
	}
	response, content = harness.request(t, http.MethodGet, "/v1/status", nil, nil)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d: %s", response.StatusCode, content)
	}
}

func createOperationBody(t *testing.T, deviceID, opID string) []byte {
	t.Helper()
	body, err := json.Marshal(model.Operation{
		OpID:     opID,
		DeviceID: deviceID,
		Type:     "task.create",
		Task: &model.Task{
			TaskID:      "task-http",
			Title:       "交方案",
			TitleSource: model.TitleSourceManual,
			CreatedOn:   "2026-11-18",
			DueDate:     "2026-11-18",
			Priority:    model.PriorityNormal,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	return body
}
