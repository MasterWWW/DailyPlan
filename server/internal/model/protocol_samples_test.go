package model

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func loadProtocolSample(t *testing.T, name string, target any) {
	t.Helper()
	path := filepath.Join("..", "..", "..", "shared", "protocol", "samples", "server", name)
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	if err := json.Unmarshal(content, target); err != nil {
		t.Fatalf("decode %s: %v", path, err)
	}
}

func TestProtocolSamplesDecode(t *testing.T) {
	var status Status
	loadProtocolSample(t, "status.json", &status)
	if status.ProtocolVersion != 1 || status.Cursor != 12 || !status.TranscriptionEnabled {
		t.Fatalf("unexpected status sample: %#v", status)
	}

	var taskCreate Operation
	loadProtocolSample(t, "operation-task-create.json", &taskCreate)
	if err := taskCreate.Validate(time.Now().UTC()); err != nil {
		t.Fatalf("validate task.create sample: %v", err)
	}
	if taskCreate.Task == nil || taskCreate.Task.DueTime != nil {
		t.Fatalf("unexpected task.create sample: %#v", taskCreate)
	}

	var taskUpdate Operation
	loadProtocolSample(t, "operation-task-update.json", &taskUpdate)
	if err := taskUpdate.Validate(time.Now().UTC()); err != nil {
		t.Fatalf("validate task.update sample: %v", err)
	}
	if taskUpdate.Set == nil || !taskUpdate.Set.DueTimeSet ||
		taskUpdate.Set.DueTime == nil || *taskUpdate.Set.DueTime != "15:00" {
		t.Fatalf("unexpected task.update sample: %#v", taskUpdate.Set)
	}

	var settingsUpdate Operation
	loadProtocolSample(t, "operation-settings-update.json", &settingsUpdate)
	if err := settingsUpdate.Validate(time.Now().UTC()); err != nil {
		t.Fatalf("validate settings.update sample: %v", err)
	}

	var receipt OperationReceipt
	loadProtocolSample(t, "operation-receipt.json", &receipt)
	if receipt.OpID == "" || receipt.Cursor != 1 || receipt.Task == nil {
		t.Fatalf("unexpected receipt sample: %#v", receipt)
	}

	var changes ChangesPage
	loadProtocolSample(t, "changes-page.json", &changes)
	if len(changes.Changes) != 1 || changes.NextCursor != 12 || changes.Changes[0].Task == nil {
		t.Fatalf("unexpected changes sample: %#v", changes)
	}

	var snapshot Snapshot
	loadProtocolSample(t, "snapshot.json", &snapshot)
	if snapshot.Cursor != 12 || len(snapshot.Tasks) != 1 || len(snapshot.Attachments) != 0 {
		t.Fatalf("unexpected snapshot sample: %#v", snapshot)
	}

	var attachment Attachment
	loadProtocolSample(t, "attachment.json", &attachment)
	if attachment.Kind != AttachmentRecording ||
		attachment.TranscriptionStatus == nil ||
		*attachment.TranscriptionStatus != TranscriptionPending {
		t.Fatalf("unexpected attachment sample: %#v", attachment)
	}
}
