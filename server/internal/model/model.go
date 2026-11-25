package model

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	PriorityNormal     = "normal"
	PriorityUrgent     = "urgent"
	PriorityVeryUrgent = "very_urgent"

	TitleSourceManual       = "manual"
	TitleSourceVoice        = "voice_placeholder"
	TitleSourceScreenshot   = "screenshot_placeholder"
	AttachmentScreenshot    = "screenshot"
	AttachmentRecording     = "recording"
	TranscriptionPending    = "pending"
	TranscriptionProcessing = "processing"
	TranscriptionComplete   = "complete"
	TranscriptionFailed     = "failed"
)

var (
	ErrNotFound = errors.New("not found")
	ErrConflict = errors.New("conflict")
	ErrInvalid  = errors.New("invalid input")
	ErrReadOnly = errors.New("read only")
)

type Task struct {
	TaskID      string     `json:"taskId"`
	Title       string     `json:"title"`
	TitleSource string     `json:"titleSource"`
	CreatedOn   string     `json:"createdOn"`
	DueDate     string     `json:"dueDate"`
	DueTime     *string    `json:"dueTime"`
	Priority    string     `json:"priority"`
	Completed   bool       `json:"completed"`
	CompletedAt *time.Time `json:"completedAt"`
	UpdatedAt   time.Time  `json:"updatedAt"`
}

type WorkSettings struct {
	Workdays            []int  `json:"workdays"`
	TimeZone            string `json:"timeZone"`
	WorkStart           string `json:"workStart"`
	WorkEnd             string `json:"workEnd"`
	ReminderLeadMinutes int    `json:"reminderLeadMinutes"`
}

func DefaultWorkSettings() WorkSettings {
	return WorkSettings{
		Workdays:            []int{1, 2, 3, 4, 5},
		TimeZone:            "Asia/Shanghai",
		WorkStart:           "09:00",
		WorkEnd:             "18:00",
		ReminderLeadMinutes: 15,
	}
}

type Attachment struct {
	AttachmentID        string    `json:"attachmentId"`
	TaskID              string    `json:"taskId"`
	Kind                string    `json:"kind"`
	MediaType           string    `json:"mediaType"`
	ByteSize            int64     `json:"byteSize"`
	SHA256              string    `json:"sha256"`
	CreatedAt           time.Time `json:"createdAt"`
	TranscriptionStatus *string   `json:"transcriptionStatus,omitempty"`
	Transcript          *string   `json:"transcript,omitempty"`
	TranscriptionError  *string   `json:"transcriptionError,omitempty"`
	StorageKey          string    `json:"-"`
}

type Status struct {
	ProtocolVersion      int       `json:"protocolVersion"`
	ServerTime           time.Time `json:"serverTime"`
	DatasetID            string    `json:"datasetId"`
	RestoreVersion       int64     `json:"restoreVersion"`
	Cursor               int64     `json:"cursor"`
	TranscriptionEnabled bool      `json:"transcriptionEnabled"`
}

type TaskPatch struct {
	Title       *string
	TitleSource *string
	DueDate     *string
	DueTime     *string
	DueTimeSet  bool
	Priority    *string
	Completed   *bool
}

func (p *TaskPatch) UnmarshalJSON(data []byte) error {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	for key, value := range raw {
		switch key {
		case "title":
			if err := decodeString(value, &p.Title); err != nil {
				return fmt.Errorf("title: %w", err)
			}
		case "titleSource":
			if err := decodeString(value, &p.TitleSource); err != nil {
				return fmt.Errorf("titleSource: %w", err)
			}
		case "dueDate":
			if err := decodeString(value, &p.DueDate); err != nil {
				return fmt.Errorf("dueDate: %w", err)
			}
		case "dueTime":
			p.DueTimeSet = true
			if bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
				p.DueTime = nil
				continue
			}
			if err := decodeString(value, &p.DueTime); err != nil {
				return fmt.Errorf("dueTime: %w", err)
			}
		case "priority":
			if err := decodeString(value, &p.Priority); err != nil {
				return fmt.Errorf("priority: %w", err)
			}
		case "completed":
			if err := json.Unmarshal(value, &p.Completed); err != nil {
				return fmt.Errorf("completed: %w", err)
			}
		default:
			return fmt.Errorf("unknown task field %q", key)
		}
	}
	if len(raw) == 0 {
		return ErrInvalid
	}
	return nil
}

func decodeString(raw json.RawMessage, target **string) error {
	if bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		*target = nil
		return nil
	}
	var value string
	if err := json.Unmarshal(raw, &value); err != nil {
		return err
	}
	*target = &value
	return nil
}

func (p TaskPatch) FieldCount() int {
	count := 0
	if p.Title != nil {
		count++
	}
	if p.TitleSource != nil {
		count++
	}
	if p.DueDate != nil {
		count++
	}
	if p.DueTimeSet {
		count++
	}
	if p.Priority != nil {
		count++
	}
	if p.Completed != nil {
		count++
	}
	return count
}

type Operation struct {
	OpID       string        `json:"opId"`
	DeviceID   string        `json:"deviceId"`
	Type       string        `json:"type"`
	OccurredAt *time.Time    `json:"occurredAt,omitempty"`
	Task       *Task         `json:"task,omitempty"`
	TaskID     string        `json:"taskId,omitempty"`
	Set        *TaskPatch    `json:"set,omitempty"`
	Fallback   *Task         `json:"fallback,omitempty"`
	Settings   *WorkSettings `json:"settings,omitempty"`
}

type OperationReceipt struct {
	OpID     string        `json:"opId"`
	Cursor   int64         `json:"cursor"`
	Task     *Task         `json:"task,omitempty"`
	Settings *WorkSettings `json:"settings,omitempty"`
}

type TaskMutation struct {
	Task    Task
	Created bool
}

type SettingsMutation struct {
	Settings WorkSettings
}

type Change struct {
	Seq        int64         `json:"seq"`
	Type       string        `json:"type"`
	Task       *Task         `json:"task,omitempty"`
	Settings   *WorkSettings `json:"settings,omitempty"`
	Attachment *Attachment   `json:"attachment,omitempty"`
}

type ChangesPage struct {
	Changes    []Change `json:"changes"`
	NextCursor int64    `json:"nextCursor"`
	HasMore    bool     `json:"hasMore"`
}

type Snapshot struct {
	Cursor         int64        `json:"cursor"`
	RestoreVersion int64        `json:"restoreVersion"`
	Tasks          []Task       `json:"tasks"`
	Settings       WorkSettings `json:"settings"`
	Attachments    []Attachment `json:"attachments"`
}

type TranscriptionJob struct {
	AttachmentID string
	TaskID       string
	StorageKey   string
	MediaType    string
	Attempts     int
}

type BackupInfo struct {
	DatasetID      string
	RestoreVersion int64
	TaskCount      int64
	Attachments    []Attachment
}

func (t *Task) Normalize(now time.Time) error {
	t.TaskID = strings.TrimSpace(t.TaskID)
	t.Title = strings.TrimSpace(t.Title)
	t.TitleSource = strings.TrimSpace(t.TitleSource)
	t.CreatedOn = strings.TrimSpace(t.CreatedOn)
	t.DueDate = strings.TrimSpace(t.DueDate)
	t.Priority = strings.TrimSpace(t.Priority)
	if t.DueTime != nil {
		value := strings.TrimSpace(*t.DueTime)
		t.DueTime = &value
	}
	if err := ValidateID(t.TaskID, "taskId"); err != nil {
		return err
	}
	if t.Title == "" || utf8.RuneCountInString(t.Title) > 500 {
		return fmt.Errorf("%w: title must contain 1 to 500 characters", ErrInvalid)
	}
	if !validTitleSource(t.TitleSource) {
		return fmt.Errorf("%w: invalid titleSource", ErrInvalid)
	}
	if _, err := time.Parse("2006-01-02", t.CreatedOn); err != nil {
		return fmt.Errorf("%w: createdOn must be YYYY-MM-DD", ErrInvalid)
	}
	if _, err := time.Parse("2006-01-02", t.DueDate); err != nil {
		return fmt.Errorf("%w: dueDate must be YYYY-MM-DD", ErrInvalid)
	}
	if t.DueTime != nil && !validClock(*t.DueTime) {
		return fmt.Errorf("%w: dueTime must be HH:mm", ErrInvalid)
	}
	if !validPriority(t.Priority) {
		return fmt.Errorf("%w: invalid priority", ErrInvalid)
	}
	t.UpdatedAt = now.UTC()
	if t.Completed {
		if t.CompletedAt == nil {
			value := now.UTC()
			t.CompletedAt = &value
		} else {
			value := t.CompletedAt.UTC()
			t.CompletedAt = &value
		}
	} else {
		t.CompletedAt = nil
	}
	return nil
}

func (p *TaskPatch) Validate() error {
	if p.FieldCount() == 0 {
		return fmt.Errorf("%w: task patch is empty", ErrInvalid)
	}
	if p.Title != nil {
		value := strings.TrimSpace(*p.Title)
		if value == "" || utf8.RuneCountInString(value) > 500 {
			return fmt.Errorf("%w: title must contain 1 to 500 characters", ErrInvalid)
		}
		p.Title = &value
	}
	if p.TitleSource != nil && !validTitleSource(*p.TitleSource) {
		return fmt.Errorf("%w: invalid titleSource", ErrInvalid)
	}
	if p.DueDate != nil {
		value := strings.TrimSpace(*p.DueDate)
		if _, err := time.Parse("2006-01-02", value); err != nil {
			return fmt.Errorf("%w: dueDate must be YYYY-MM-DD", ErrInvalid)
		}
		p.DueDate = &value
	}
	if p.DueTimeSet && p.DueTime != nil {
		value := strings.TrimSpace(*p.DueTime)
		if !validClock(value) {
			return fmt.Errorf("%w: dueTime must be HH:mm", ErrInvalid)
		}
		p.DueTime = &value
	}
	if p.Priority != nil && !validPriority(*p.Priority) {
		return fmt.Errorf("%w: invalid priority", ErrInvalid)
	}
	return nil
}

func (w *WorkSettings) Normalize() error {
	w.TimeZone = strings.TrimSpace(w.TimeZone)
	w.WorkStart = strings.TrimSpace(w.WorkStart)
	w.WorkEnd = strings.TrimSpace(w.WorkEnd)
	if len(w.Workdays) == 0 {
		return fmt.Errorf("%w: at least one workday is required", ErrInvalid)
	}
	seen := make(map[int]bool, len(w.Workdays))
	days := make([]int, 0, len(w.Workdays))
	for _, day := range w.Workdays {
		if day < 1 || day > 7 {
			return fmt.Errorf("%w: workdays must be ISO weekdays 1 through 7", ErrInvalid)
		}
		if !seen[day] {
			seen[day] = true
			days = append(days, day)
		}
	}
	sort.Ints(days)
	w.Workdays = days
	if w.TimeZone == "" {
		return fmt.Errorf("%w: timeZone is required", ErrInvalid)
	}
	if _, err := time.LoadLocation(w.TimeZone); err != nil {
		return fmt.Errorf("%w: unknown timeZone %q", ErrInvalid, w.TimeZone)
	}
	if !validClock(w.WorkStart) || !validClock(w.WorkEnd) {
		return fmt.Errorf("%w: work hours must be HH:mm", ErrInvalid)
	}
	startMinutes := clockMinutes(w.WorkStart)
	endMinutes := clockMinutes(w.WorkEnd)
	if startMinutes >= endMinutes {
		return fmt.Errorf("%w: workStart must be before workEnd", ErrInvalid)
	}
	if w.ReminderLeadMinutes < 0 || w.ReminderLeadMinutes > 600 {
		return fmt.Errorf("%w: reminderLeadMinutes must be between 0 and 600", ErrInvalid)
	}
	return nil
}

func (o *Operation) Validate(now time.Time) error {
	o.OpID = strings.TrimSpace(o.OpID)
	o.DeviceID = strings.TrimSpace(o.DeviceID)
	if err := ValidateID(o.OpID, "opId"); err != nil {
		return err
	}
	if err := ValidateID(o.DeviceID, "deviceId"); err != nil {
		return err
	}
	switch o.Type {
	case "task.create":
		if o.Task == nil {
			return fmt.Errorf("%w: task is required", ErrInvalid)
		}
		return o.Task.Normalize(now)
	case "task.update":
		if err := ValidateID(strings.TrimSpace(o.TaskID), "taskId"); err != nil {
			return err
		}
		o.TaskID = strings.TrimSpace(o.TaskID)
		if o.Set == nil || o.Set.FieldCount() == 0 {
			return fmt.Errorf("%w: set is required", ErrInvalid)
		}
		if err := o.Set.Validate(); err != nil {
			return err
		}
		if o.Fallback != nil {
			if err := o.Fallback.Normalize(now); err != nil {
				return err
			}
			if o.Fallback.TaskID != o.TaskID {
				return fmt.Errorf("%w: fallback taskId does not match taskId", ErrInvalid)
			}
		}
		return nil
	case "task.delete":
		if err := ValidateID(strings.TrimSpace(o.TaskID), "taskId"); err != nil {
			return err
		}
		o.TaskID = strings.TrimSpace(o.TaskID)
		return nil
	case "settings.update":
		if o.Settings == nil {
			return fmt.Errorf("%w: settings is required", ErrInvalid)
		}
		return o.Settings.Normalize()
	default:
		return fmt.Errorf("%w: unknown operation type %q", ErrInvalid, o.Type)
	}
}

func ValidateID(value, field string) error {
	value = strings.TrimSpace(value)
	if value == "" || len(value) > 80 {
		return fmt.Errorf("%w: %s must contain 1 to 80 characters", ErrInvalid, field)
	}
	for _, char := range value {
		if (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') ||
			(char >= '0' && char <= '9') || char == '-' || char == '_' {
			continue
		}
		return fmt.Errorf("%w: %s contains unsupported characters", ErrInvalid, field)
	}
	return nil
}

func ValidAttachment(attachmentID, taskID, kind, mediaType, sha string, size int64) bool {
	if ValidateID(attachmentID, "attachmentId") != nil || ValidateID(taskID, "taskId") != nil {
		return false
	}
	if kind != AttachmentScreenshot && kind != AttachmentRecording {
		return false
	}
	if kind == AttachmentScreenshot {
		if mediaType != "image/png" && mediaType != "image/jpeg" {
			return false
		}
	} else if mediaType != "audio/wav" {
		return false
	}
	if size <= 0 || len(sha) != 64 {
		return false
	}
	for _, char := range sha {
		if (char < '0' || char > '9') && (char < 'a' || char > 'f') {
			return false
		}
	}
	return true
}

func validTitleSource(value string) bool {
	return value == TitleSourceManual || value == TitleSourceVoice || value == TitleSourceScreenshot
}

func validPriority(value string) bool {
	return value == PriorityNormal || value == PriorityUrgent || value == PriorityVeryUrgent
}

func validClock(value string) bool {
	_, err := time.Parse("15:04", value)
	return err == nil
}

func clockMinutes(value string) int {
	parsed, _ := time.Parse("15:04", value)
	return parsed.Hour()*60 + parsed.Minute()
}
