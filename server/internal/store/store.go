package store

import (
	"context"

	"github.com/MasterWWW/DailyPlan/server/internal/model"
)

type Store interface {
	Ping(context.Context) error
	Close() error

	Status(context.Context) (model.Status, error)
	ApplyOperation(context.Context, model.Operation, string) (model.OperationReceipt, error)
	Changes(context.Context, int64, int) (model.ChangesPage, error)
	Snapshot(context.Context) (model.Snapshot, error)

	Attachment(context.Context, string) (model.Attachment, error)
	PublishAttachment(context.Context, model.Attachment, string) (model.Attachment, bool, error)
	RetryTranscription(context.Context, string) (model.Attachment, error)
	ResetProcessingJobs(context.Context) error
	ClaimTranscription(context.Context) (*model.TranscriptionJob, error)
	FinishTranscription(context.Context, string, *string, *string) error

	BackupInfo(context.Context) (model.BackupInfo, error)
	IncrementRestoreVersion(context.Context) (int64, error)
	PingMySQL(context.Context) (string, error)
	DatabaseIsEmpty(context.Context) (bool, error)
	DropAllTables(context.Context) error
}
