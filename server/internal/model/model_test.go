package model

import (
	"encoding/json"
	"errors"
	"testing"
	"time"
)

func TestTaskPatchDecodesExplicitNullDueTime(t *testing.T) {
	var patch TaskPatch
	if err := json.Unmarshal([]byte(`{"dueTime":null}`), &patch); err != nil {
		t.Fatal(err)
	}
	if !patch.DueTimeSet {
		t.Fatal("expected dueTime to be marked as supplied")
	}
	if patch.DueTime != nil {
		t.Fatal("expected dueTime to be nil")
	}
}

func TestTaskPatchRejectsUnknownField(t *testing.T) {
	var patch TaskPatch
	if err := json.Unmarshal([]byte(`{"completd":true}`), &patch); err == nil {
		t.Fatal("expected unknown field to be rejected")
	}
}

func TestTaskPatchValidationTrimsTitle(t *testing.T) {
	title := "  核对运费  "
	patch := TaskPatch{Title: &title}
	if err := patch.Validate(); err != nil {
		t.Fatal(err)
	}
	if patch.Title == nil || *patch.Title != "核对运费" {
		t.Fatalf("expected trimmed title, got %#v", patch.Title)
	}
}

func TestOperationValidationAndNormalization(t *testing.T) {
	now := time.Date(2026, 11, 17, 1, 3, 11, 0, time.UTC)
	dueTime := "15:00"
	op := Operation{
		OpID:     "operation-1",
		DeviceID: "device-1",
		Type:     "task.create",
		Task: &Task{
			TaskID:      "task-1",
			Title:       " 核对运费 ",
			TitleSource: TitleSourceManual,
			CreatedOn:   "2026-11-17",
			DueDate:     "2026-11-17",
			DueTime:     &dueTime,
			Priority:    PriorityNormal,
		},
	}
	if err := op.Validate(now); err != nil {
		t.Fatal(err)
	}
	if op.Task.Title != "核对运费" {
		t.Fatalf("expected trimmed title, got %q", op.Task.Title)
	}
	if !op.Task.UpdatedAt.Equal(now) {
		t.Fatalf("expected server time, got %s", op.Task.UpdatedAt)
	}
}

func TestSettingsRejectInvertedHours(t *testing.T) {
	settings := DefaultWorkSettings()
	settings.WorkStart = "18:00"
	settings.WorkEnd = "09:00"
	if err := settings.Normalize(); !errors.Is(err, ErrInvalid) {
		t.Fatalf("expected invalid settings, got %v", err)
	}
}

func TestSettingsNormalizeDeduplicatesWorkdays(t *testing.T) {
	settings := DefaultWorkSettings()
	settings.Workdays = []int{5, 1, 3, 1}
	if err := settings.Normalize(); err != nil {
		t.Fatal(err)
	}
	want := []int{1, 3, 5}
	if len(settings.Workdays) != len(want) {
		t.Fatalf("got %v, want %v", settings.Workdays, want)
	}
	for i := range want {
		if settings.Workdays[i] != want[i] {
			t.Fatalf("got %v, want %v", settings.Workdays, want)
		}
	}
}

func TestValidateIDRejectsPathCharacters(t *testing.T) {
	if err := ValidateID("task/../../etc", "taskId"); err == nil {
		t.Fatal("expected unsafe id to be rejected")
	}
}

func TestValidAttachment(t *testing.T) {
	sha := "5d41402abc4b2a76b9719d911017c5925d41402abc4b2a76b9719d911017c592"
	if !ValidAttachment("attachment-1", "task-1", AttachmentRecording, "audio/wav", sha, 42) {
		t.Fatal("expected valid recording")
	}
	if ValidAttachment("attachment-1", "task-1", AttachmentRecording, "audio/mpeg", sha, 42) {
		t.Fatal("expected unsupported media type to fail")
	}
	if ValidAttachment("attachment-1", "task-1", AttachmentScreenshot, "image/png", "ABC", 42) {
		t.Fatal("expected malformed hash to fail")
	}
}
