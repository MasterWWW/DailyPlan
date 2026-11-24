package mysql

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/MasterWWW/DailyPlan/server/internal/model"
)

func testStore(t *testing.T) *Store {
	t.Helper()
	dsn := os.Getenv("DAILYPLAN_TEST_MYSQL_DSN")
	if dsn == "" {
		t.Skip("set DAILYPLAN_TEST_MYSQL_DSN to run MySQL integration tests")
	}
	datasetID := fmt.Sprintf("test-%d", time.Now().UnixNano())
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(cancel)
	dataStore, err := Open(ctx, dsn, datasetID)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = dataStore.Close() })
	return dataStore
}

func taskOperation(t *testing.T, task model.Task, suffix string) (model.Operation, string) {
	t.Helper()
	dueTime := "15:00"
	task.DueTime = &dueTime
	op := model.Operation{
		OpID:     "op-" + suffix,
		DeviceID: "device-a",
		Type:     "task.create",
		Task:     &task,
	}
	if err := op.Validate(time.Now().UTC()); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256([]byte("body-" + suffix))
	return op, hex.EncodeToString(sum[:])
}

func newTask(taskID string) model.Task {
	return model.Task{
		TaskID:      taskID,
		Title:       "交方案 " + taskID,
		TitleSource: model.TitleSourceManual,
		CreatedOn:   "2026-11-16",
		DueDate:     "2026-11-18",
		Priority:    model.PriorityNormal,
	}
}

func TestStoreTransactionsAndIdempotency(t *testing.T) {
	dataStore := testStore(t)
	ctx := context.Background()

	status, err := dataStore.Status(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if status.Cursor != 0 {
		t.Fatalf("new dataset cursor = %d, want 0", status.Cursor)
	}

	op, hash := taskOperation(t, newTask("task-1"), "create-1")
	receipt, err := dataStore.ApplyOperation(ctx, op, hash)
	if err != nil {
		t.Fatal(err)
	}
	if receipt.Cursor != 1 || receipt.Task == nil || receipt.Task.TaskID != "task-1" {
		t.Fatalf("unexpected first receipt: %#v", receipt)
	}
	repeated, err := dataStore.ApplyOperation(ctx, op, hash)
	if err != nil {
		t.Fatal(err)
	}
	if repeated.Cursor != receipt.Cursor {
		t.Fatalf("repeated op cursor = %d, want %d", repeated.Cursor, receipt.Cursor)
	}
	if _, err := dataStore.ApplyOperation(ctx, op, "different-hash"); !errors.Is(err, model.ErrConflict) {
		t.Fatalf("same op with different body: got %v, want conflict", err)
	}

	snapshot, err := dataStore.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.Cursor != 1 || len(snapshot.Tasks) != 1 {
		t.Fatalf("unexpected snapshot: cursor=%d tasks=%d", snapshot.Cursor, len(snapshot.Tasks))
	}

	newTitle := "已改标题"
	completed := true
	update := model.Operation{
		OpID:     "op-update-1",
		DeviceID: "device-b",
		Type:     "task.update",
		TaskID:   "task-1",
		Set:      &model.TaskPatch{Title: &newTitle, Completed: &completed},
	}
	if err := update.Validate(time.Now().UTC()); err != nil {
		t.Fatal(err)
	}
	updateReceipt, err := dataStore.ApplyOperation(ctx, update, hashString("update-1"))
	if err != nil {
		t.Fatal(err)
	}
	if updateReceipt.Cursor != 2 || updateReceipt.Task.Title != newTitle || !updateReceipt.Task.Completed {
		t.Fatalf("unexpected update receipt: %#v", updateReceipt)
	}
	if updateReceipt.Task.CompletedAt == nil {
		t.Fatal("expected completedAt to be set")
	}

	settings := model.DefaultWorkSettings()
	settings.WorkStart = "08:30"
	settingsOp := model.Operation{
		OpID:     "op-settings-1",
		DeviceID: "device-a",
		Type:     "settings.update",
		Settings: &settings,
	}
	if err := settingsOp.Validate(time.Now().UTC()); err != nil {
		t.Fatal(err)
	}
	settingsReceipt, err := dataStore.ApplyOperation(ctx, settingsOp, hashString("settings-1"))
	if err != nil {
		t.Fatal(err)
	}
	if settingsReceipt.Cursor != 3 || settingsReceipt.Settings.WorkStart != "08:30" {
		t.Fatalf("unexpected settings receipt: %#v", settingsReceipt)
	}

	firstPage, err := dataStore.Changes(ctx, 0, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(firstPage.Changes) != 1 || firstPage.NextCursor != 1 || !firstPage.HasMore {
		t.Fatalf("unexpected first page: %#v", firstPage)
	}
}

func TestConcurrentOperationsHaveContiguousCursor(t *testing.T) {
	dataStore := testStore(t)
	ctx := context.Background()
	const count = 8
	var wait sync.WaitGroup
	errs := make(chan error, count)
	for i := 0; i < count; i++ {
		wait.Add(1)
		go func(index int) {
			defer wait.Done()
			taskID := fmt.Sprintf("concurrent-%d", index)
			op, hash := taskOperation(t, newTask(taskID), fmt.Sprintf("concurrent-%d", index))
			if _, err := dataStore.ApplyOperation(ctx, op, hash); err != nil {
				errs <- err
			}
		}(i)
	}
	wait.Wait()
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}

	status, err := dataStore.Status(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if status.Cursor != count {
		t.Fatalf("cursor = %d, want %d", status.Cursor, count)
	}
	page, err := dataStore.Changes(ctx, 0, 200)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Changes) != count {
		t.Fatalf("changes = %d, want %d", len(page.Changes), count)
	}
	for index, change := range page.Changes {
		want := int64(index + 1)
		if change.Seq != want {
			t.Fatalf("change %d seq = %d, want %d", index, change.Seq, want)
		}
	}
}

func TestConcurrentRetryOfSameOperationProvesExactlyOnce(t *testing.T) {
	dataStore := testStore(t)
	ctx := context.Background()
	op, hash := taskOperation(t, newTask("deduplicated"), "deduplicated")
	const count = 8
	var wait sync.WaitGroup
	receipts := make(chan model.OperationReceipt, count)
	errs := make(chan error, count)
	for i := 0; i < count; i++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			receipt, err := dataStore.ApplyOperation(ctx, op, hash)
			if err != nil {
				errs <- err
				return
			}
			receipts <- receipt
		}()
	}
	wait.Wait()
	close(receipts)
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
	cursor := int64(-1)
	for receipt := range receipts {
		if cursor == -1 {
			cursor = receipt.Cursor
		}
		if receipt.Cursor != cursor {
			t.Fatalf("receipt cursors differ: %d and %d", cursor, receipt.Cursor)
		}
	}
	status, err := dataStore.Status(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if status.Cursor != 1 {
		t.Fatalf("cursor = %d, want exactly one change", status.Cursor)
	}
	snapshot, err := dataStore.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(snapshot.Tasks) != 1 {
		t.Fatalf("tasks = %d, want 1", len(snapshot.Tasks))
	}
}

func TestTranscriptionLifecyclePreservesManualContent(t *testing.T) {
	dataStore := testStore(t)
	ctx := context.Background()
	task := newTask("voice-task")
	task.Title = "语音待办 · 09:30"
	task.TitleSource = model.TitleSourceVoice
	op, hash := taskOperation(t, task, "voice-create")
	if _, err := dataStore.ApplyOperation(ctx, op, hash); err != nil {
		t.Fatal(err)
	}

	attachment := model.Attachment{
		AttachmentID: "recording-1",
		TaskID:       "voice-task",
		Kind:         model.AttachmentRecording,
		MediaType:    "audio/wav",
		ByteSize:     48044,
		SHA256:       hashString("recording"),
	}
	published, created, err := dataStore.PublishAttachment(ctx, attachment, "recording-1.wav")
	if err != nil {
		t.Fatal(err)
	}
	if !created || published.TranscriptionStatus == nil || *published.TranscriptionStatus != model.TranscriptionPending {
		t.Fatalf("unexpected published attachment: %#v", published)
	}
	job, err := dataStore.ClaimTranscription(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if job.AttachmentID != "recording-1" || job.Attempts != 1 {
		t.Fatalf("unexpected job: %#v", job)
	}
	transcript := "明早给客户回电话"
	if err := dataStore.FinishTranscription(ctx, "recording-1", &transcript, nil); err != nil {
		t.Fatal(err)
	}
	snapshot, err := dataStore.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.Tasks[0].Title != transcript || snapshot.Tasks[0].TitleSource != model.TitleSourceManual {
		t.Fatalf("unexpected transcribed task: %#v", snapshot.Tasks[0])
	}
	if snapshot.Attachments[0].TranscriptionStatus == nil ||
		*snapshot.Attachments[0].TranscriptionStatus != model.TranscriptionComplete {
		t.Fatalf("unexpected attachment state: %#v", snapshot.Attachments[0])
	}
}

func TestPublishAttachmentRejectsReusedIDWithDifferentSize(t *testing.T) {
	dataStore := testStore(t)
	ctx := context.Background()
	op, hash := taskOperation(t, newTask("attachment-size-task"), "attachment-size-task")
	if _, err := dataStore.ApplyOperation(ctx, op, hash); err != nil {
		t.Fatal(err)
	}
	attachment := model.Attachment{
		AttachmentID: "attachment-size",
		TaskID:       "attachment-size-task",
		Kind:         model.AttachmentScreenshot,
		MediaType:    "image/png",
		ByteSize:     8,
		SHA256:       hashString("attachment-size"),
	}
	if _, created, err := dataStore.PublishAttachment(ctx, attachment, "attachment-size.png"); err != nil || !created {
		t.Fatalf("publish original attachment: created=%v err=%v", created, err)
	}
	attachment.ByteSize++
	if _, _, err := dataStore.PublishAttachment(ctx, attachment, "attachment-size.png"); !errors.Is(err, model.ErrConflict) {
		t.Fatalf("reused attachment size error = %v, want conflict", err)
	}
}

func hashString(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}
