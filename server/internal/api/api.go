package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/MasterWWW/DailyPlan/server/internal/config"
	"github.com/MasterWWW/DailyPlan/server/internal/model"
	"github.com/MasterWWW/DailyPlan/server/internal/storage"
	"github.com/MasterWWW/DailyPlan/server/internal/store"
)

const maxOperationBody = 1 << 20

type API struct {
	store                store.Store
	attachments          *storage.Store
	cfg                  config.Config
	logger               *log.Logger
	transcriptionEnabled bool
	uploadMu             sync.Mutex
}

func New(
	dataStore store.Store,
	attachments *storage.Store,
	cfg config.Config,
	logger *log.Logger,
	transcriptionEnabled bool,
) http.Handler {
	api := &API{
		store:                dataStore,
		attachments:          attachments,
		cfg:                  cfg,
		logger:               logger,
		transcriptionEnabled: transcriptionEnabled,
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", api.health)
	mux.HandleFunc("GET /v1/status", api.status)
	mux.HandleFunc("POST /v1/operations", api.operations)
	mux.HandleFunc("GET /v1/changes", api.changes)
	mux.HandleFunc("GET /v1/snapshot", api.snapshot)
	mux.HandleFunc("PUT /v1/attachments/{attachmentId}", api.uploadAttachment)
	mux.HandleFunc("GET /v1/attachments/{attachmentId}", api.downloadAttachment)
	mux.HandleFunc("POST /v1/attachments/{attachmentId}/transcription/retry", api.retryTranscription)
	return api.middleware(mux)
}

func (a *API) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		statusWriter := &statusWriter{ResponseWriter: w, status: http.StatusOK}
		defer func() {
			if recovered := recover(); recovered != nil {
				a.logger.Printf("panic serving %s %s: %v", r.Method, r.URL.Path, recovered)
				writeError(statusWriter, http.StatusInternalServerError, "internal_error", "服务器暂时不可用")
			}
			a.logger.Printf("%s %s %d %s", r.Method, r.URL.Path, statusWriter.status, time.Since(start).Round(time.Millisecond))
		}()

		statusWriter.Header().Set("Cache-Control", "no-store")
		statusWriter.Header().Set("X-Content-Type-Options", "nosniff")
		statusWriter.Header().Set("Referrer-Policy", "no-referrer")

		if r.URL.Path != "/healthz" && !a.authorized(r) {
			statusWriter.Header().Set("WWW-Authenticate", `Bearer realm="DailyPlan"`)
			writeError(statusWriter, http.StatusUnauthorized, "unauthorized", "访问令牌无效")
			return
		}
		if a.cfg.ReadOnly && isMutation(r.Method) {
			writeError(statusWriter, http.StatusServiceUnavailable, "read_only", "服务正在备份迁移，暂时只读")
			return
		}
		next.ServeHTTP(statusWriter, r)
	})
}

func (a *API) authorized(r *http.Request) bool {
	header := strings.TrimSpace(r.Header.Get("Authorization"))
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) {
		return false
	}
	provided := strings.TrimSpace(strings.TrimPrefix(header, prefix))
	if provided == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(provided), []byte(a.cfg.APIToken)) == 1
}

func isMutation(method string) bool {
	return method == http.MethodPost || method == http.MethodPut || method == http.MethodPatch ||
		method == http.MethodDelete
}

func (a *API) health(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if err := a.store.Ping(ctx); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "unavailable"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (a *API) status(w http.ResponseWriter, r *http.Request) {
	status, err := a.store.Status(r.Context())
	if err != nil {
		a.fail(w, err)
		return
	}
	status.TranscriptionEnabled = a.transcriptionEnabled
	writeJSON(w, http.StatusOK, status)
}

func (a *API) operations(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxOperationBody))
	if err != nil {
		writeError(w, http.StatusRequestEntityTooLarge, "body_too_large", "请求内容过大")
		return
	}
	var op model.Operation
	if err := decodeStrict(body, &op); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "请求格式不正确")
		return
	}
	now := time.Now().UTC()
	if err := op.Validate(now); err != nil {
		a.fail(w, err)
		return
	}
	hash := sha256.Sum256(body)
	receipt, removed, err := a.store.ApplyOperation(r.Context(), op, hex.EncodeToString(hash[:]))
	if err != nil {
		a.fail(w, err)
		return
	}
	for _, attachment := range removed {
		a.attachments.Remove(attachment.StorageKey)
	}
	writeJSON(w, http.StatusOK, receipt)
}

func (a *API) changes(w http.ResponseWriter, r *http.Request) {
	after, err := parseIntQuery(r, "after", 0, 0, 1<<62)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_cursor", "after 必须是非负整数")
		return
	}
	limit64, err := parseIntQuery(r, "limit", 100, 1, 200)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_limit", "limit 必须是 1 到 200")
		return
	}
	page, err := a.store.Changes(r.Context(), after, int(limit64))
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, page)
}

func (a *API) snapshot(w http.ResponseWriter, r *http.Request) {
	snapshot, err := a.store.Snapshot(r.Context())
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, snapshot)
}

func (a *API) uploadAttachment(w http.ResponseWriter, r *http.Request) {
	attachmentID := r.PathValue("attachmentId")
	taskID := r.URL.Query().Get("taskId")
	kind := r.URL.Query().Get("kind")
	mediaType := r.URL.Query().Get("mediaType")
	sha := strings.ToLower(strings.TrimSpace(r.Header.Get("X-Content-SHA256")))
	if !model.ValidAttachment(attachmentID, taskID, kind, mediaType, sha, 1) {
		writeError(w, http.StatusBadRequest, "invalid_attachment", "附件参数无效")
		return
	}
	contentLength, err := strconv.ParseInt(r.Header.Get("Content-Length"), 10, 64)
	if err != nil || contentLength <= 0 {
		writeError(w, http.StatusLengthRequired, "content_length_required", "需要 Content-Length")
		return
	}
	if contentLength > a.cfg.MaxUploadBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "attachment_too_large", "附件超过允许大小")
		return
	}

	a.uploadMu.Lock()
	defer a.uploadMu.Unlock()

	existing, err := a.store.Attachment(r.Context(), attachmentID)
	if err == nil {
		if existing.SHA256 != sha || existing.TaskID != taskID || existing.Kind != kind ||
			existing.MediaType != mediaType || existing.ByteSize != contentLength {
			writeError(w, http.StatusConflict, "attachment_conflict", "附件 ID 已用于其他内容")
			return
		}
		temp, err := a.attachments.WriteTemp(io.LimitReader(r.Body, contentLength), contentLength)
		if err != nil {
			a.fail(w, err)
			return
		}
		if temp.Size != contentLength || temp.SHA256 != sha {
			_ = os.Remove(temp.Path)
			writeError(w, http.StatusUnprocessableEntity, "checksum_mismatch", "附件长度或摘要不匹配")
			return
		}
		matches, err := a.attachments.Matches(existing.StorageKey, existing.ByteSize, existing.SHA256)
		if err != nil {
			_ = os.Remove(temp.Path)
			a.fail(w, err)
			return
		}
		if matches {
			_ = os.Remove(temp.Path)
			writeJSON(w, http.StatusOK, existing)
			return
		}
		key, err := a.attachments.Archive(temp, attachmentID, mediaType)
		if err != nil {
			_ = os.Remove(temp.Path)
			a.fail(w, err)
			return
		}
		if key != existing.StorageKey {
			a.attachments.Remove(key)
			a.logger.Printf("attachment %s storage key changed from %s to %s", attachmentID, existing.StorageKey, key)
			writeError(w, http.StatusInternalServerError, "internal_error", "服务器暂时不可用")
			return
		}
		writeJSON(w, http.StatusOK, existing)
		return
	}
	if !errors.Is(err, model.ErrNotFound) {
		a.fail(w, err)
		return
	}

	temp, err := a.attachments.WriteTemp(r.Body, a.cfg.MaxUploadBytes)
	if err != nil {
		a.fail(w, err)
		return
	}
	if temp.Size != contentLength || temp.SHA256 != sha {
		_ = os.Remove(temp.Path)
		writeError(w, http.StatusUnprocessableEntity, "checksum_mismatch", "附件长度或摘要不匹配")
		return
	}
	key, err := a.attachments.Archive(temp, attachmentID, mediaType)
	if err != nil {
		_ = os.Remove(temp.Path)
		a.fail(w, err)
		return
	}
	attachment := model.Attachment{
		AttachmentID: attachmentID,
		TaskID:       taskID,
		Kind:         kind,
		MediaType:    mediaType,
		ByteSize:     temp.Size,
		SHA256:       temp.SHA256,
	}
	published, created, err := a.store.PublishAttachment(r.Context(), attachment, key)
	if err != nil {
		if _, lookupErr := a.store.Attachment(r.Context(), attachmentID); errors.Is(lookupErr, model.ErrNotFound) {
			a.attachments.Remove(key)
		}
		a.fail(w, err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, published)
}

func (a *API) downloadAttachment(w http.ResponseWriter, r *http.Request) {
	attachment, err := a.store.Attachment(r.Context(), r.PathValue("attachmentId"))
	if err != nil {
		a.fail(w, err)
		return
	}
	file, err := a.attachments.Open(attachment.StorageKey)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			a.logger.Printf("attachment %s metadata exists but file %s is missing", attachment.AttachmentID, attachment.StorageKey)
			writeError(w, http.StatusNotFound, "attachment_file_missing", "附件原件不存在")
			return
		}
		a.fail(w, err)
		return
	}
	defer file.Close()
	w.Header().Set("Content-Type", attachment.MediaType)
	w.Header().Set("Content-Disposition", `inline; filename="`+attachment.AttachmentID+`"`)
	w.Header().Set("ETag", `"`+attachment.SHA256+`"`)
	http.ServeContent(w, r, attachment.AttachmentID, attachment.CreatedAt, file)
}

func (a *API) retryTranscription(w http.ResponseWriter, r *http.Request) {
	attachment, err := a.store.RetryTranscription(r.Context(), r.PathValue("attachmentId"))
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusAccepted, attachment)
}

func (a *API) fail(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, model.ErrInvalid):
		writeError(w, http.StatusBadRequest, "invalid_request", err.Error())
	case errors.Is(err, model.ErrConflict):
		writeError(w, http.StatusConflict, "conflict", err.Error())
	case errors.Is(err, model.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "请求的数据不存在")
	case errors.Is(err, model.ErrReadOnly):
		writeError(w, http.StatusServiceUnavailable, "read_only", "服务正在备份迁移，暂时只读")
	case errors.Is(err, storage.ErrTooLarge):
		writeError(w, http.StatusRequestEntityTooLarge, "attachment_too_large", "附件超过允许大小")
	default:
		a.logger.Printf("request failed: %v", err)
		writeError(w, http.StatusInternalServerError, "internal_error", "服务器暂时不可用")
	}
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(value); err != nil {
		_ = err
	}
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]any{
		"error": map[string]string{
			"code":    code,
			"message": message,
		},
	})
}

func decodeStrict(body []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return fmt.Errorf("request contains trailing JSON")
	}
	return nil
}

func parseIntQuery(r *http.Request, name string, fallback, minimum, maximum int64) (int64, error) {
	value := strings.TrimSpace(r.URL.Query().Get(name))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil || parsed < minimum || parsed > maximum {
		return 0, fmt.Errorf("invalid %s", name)
	}
	return parsed, nil
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}
