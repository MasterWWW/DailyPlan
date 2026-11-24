package mysql

import (
	"context"
	"database/sql"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"sort"
	"strconv"
	"strings"
	"time"

	driver "github.com/go-sql-driver/mysql"

	"github.com/MasterWWW/DailyPlan/server/internal/model"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

type Store struct {
	db        *sql.DB
	datasetID string
}

var _ interface {
	Ping(context.Context) error
	Close() error
	Status(context.Context) (model.Status, error)
} = (*Store)(nil)

func Open(ctx context.Context, dsn, datasetID string) (*Store, error) {
	return open(ctx, dsn, datasetID, true)
}

func OpenExisting(ctx context.Context, dsn, datasetID string) (*Store, error) {
	return open(ctx, dsn, datasetID, false)
}

func open(ctx context.Context, dsn, datasetID string, initialize bool) (*Store, error) {
	parsed, err := driver.ParseDSN(dsn)
	if err != nil {
		return nil, fmt.Errorf("parse MySQL DSN: %w", err)
	}
	parsed.ParseTime = true
	parsed.Loc = time.UTC
	db, err := sql.Open("mysql", parsed.FormatDSN())
	if err != nil {
		return nil, fmt.Errorf("open MySQL: %w", err)
	}
	db.SetMaxOpenConns(24)
	db.SetMaxIdleConns(8)
	db.SetConnMaxLifetime(30 * time.Minute)

	store := &Store{db: db, datasetID: datasetID}
	if err := store.Ping(ctx); err != nil {
		_ = db.Close()
		return nil, err
	}
	if !initialize {
		return store, nil
	}
	if err := store.migrate(ctx); err != nil {
		_ = db.Close()
		return nil, err
	}
	if err := store.initializeDataset(ctx); err != nil {
		_ = db.Close()
		return nil, err
	}
	return store, nil
}

func (s *Store) Ping(ctx context.Context) error {
	if err := s.db.PingContext(ctx); err != nil {
		return fmt.Errorf("ping MySQL: %w", err)
	}
	return nil
}

func (s *Store) Close() error {
	return s.db.Close()
}

func (s *Store) migrate(ctx context.Context) error {
	conn, err := s.db.Conn(ctx)
	if err != nil {
		return fmt.Errorf("reserve migration connection: %w", err)
	}
	defer conn.Close()

	var acquired int
	if err := conn.QueryRowContext(ctx, "SELECT GET_LOCK('dailyplan_schema_migration', 20)").Scan(&acquired); err != nil {
		return fmt.Errorf("acquire migration lock: %w", err)
	}
	if acquired != 1 {
		return fmt.Errorf("acquire migration lock: timed out")
	}
	defer func() {
		_, _ = conn.ExecContext(context.Background(), "SELECT RELEASE_LOCK('dailyplan_schema_migration')")
	}()

	if _, err := conn.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS schema_migrations (
		  version BIGINT NOT NULL PRIMARY KEY,
		  applied_at DATETIME(6) NOT NULL
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}

	entries, err := fs.ReadDir(migrationFiles, "migrations")
	if err != nil {
		return fmt.Errorf("read migrations: %w", err)
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".sql") {
			continue
		}
		versionPart := strings.SplitN(entry.Name(), "_", 2)[0]
		version, err := strconv.ParseInt(versionPart, 10, 64)
		if err != nil {
			return fmt.Errorf("migration filename %q: %w", entry.Name(), err)
		}
		var applied int
		if err := conn.QueryRowContext(ctx,
			"SELECT COUNT(*) FROM schema_migrations WHERE version = ?", version).Scan(&applied); err != nil {
			return fmt.Errorf("check migration %d: %w", version, err)
		}
		if applied > 0 {
			continue
		}
		body, err := migrationFiles.ReadFile("migrations/" + entry.Name())
		if err != nil {
			return fmt.Errorf("read migration %s: %w", entry.Name(), err)
		}
		for _, statement := range splitStatements(string(body)) {
			if _, err := conn.ExecContext(ctx, statement); err != nil {
				return fmt.Errorf("apply migration %s: %w", entry.Name(), err)
			}
		}
		if _, err := conn.ExecContext(ctx,
			"INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)", version, time.Now().UTC()); err != nil {
			return fmt.Errorf("record migration %d: %w", version, err)
		}
	}
	return nil
}

func splitStatements(body string) []string {
	var statements []string
	var current strings.Builder
	var quote byte
	lineComment := false
	blockComment := false
	for index := 0; index < len(body); index++ {
		char := body[index]
		if lineComment {
			current.WriteByte(char)
			if char == '\n' {
				lineComment = false
			}
			continue
		}
		if blockComment {
			current.WriteByte(char)
			if char == '*' && index+1 < len(body) && body[index+1] == '/' {
				index++
				current.WriteByte('/')
				blockComment = false
			}
			continue
		}
		if quote != 0 {
			current.WriteByte(char)
			if char == '\\' && quote != '`' && index+1 < len(body) {
				index++
				current.WriteByte(body[index])
				continue
			}
			if char == quote {
				if index+1 < len(body) && body[index+1] == quote {
					index++
					current.WriteByte(quote)
					continue
				}
				quote = 0
			}
			continue
		}
		switch char {
		case '\'', '"', '`':
			quote = char
			current.WriteByte(char)
		case '-':
			if index+1 < len(body) && body[index+1] == '-' {
				lineComment = true
			}
			current.WriteByte(char)
		case '#':
			lineComment = true
			current.WriteByte(char)
		case '/':
			if index+1 < len(body) && body[index+1] == '*' {
				blockComment = true
			}
			current.WriteByte(char)
		case ';':
			appendStatement(&statements, current.String())
			current.Reset()
		default:
			current.WriteByte(char)
		}
	}
	appendStatement(&statements, current.String())
	return statements
}

func appendStatement(statements *[]string, body string) {
	trimmed := strings.TrimSpace(body)
	if trimmed == "" || statementIsOnlyComments(trimmed) {
		return
	}
	*statements = append(*statements, trimmed)
}

func statementIsOnlyComments(body string) bool {
	lineComment := false
	blockComment := false
	for index := 0; index < len(body); index++ {
		char := body[index]
		if lineComment {
			if char == '\n' {
				lineComment = false
			}
			continue
		}
		if blockComment {
			if char == '*' && index+1 < len(body) && body[index+1] == '/' {
				index++
				blockComment = false
			}
			continue
		}
		if char == '-' && index+1 < len(body) && body[index+1] == '-' {
			lineComment = true
			index++
			continue
		}
		if char == '#' {
			lineComment = true
			continue
		}
		if char == '/' && index+1 < len(body) && body[index+1] == '*' {
			blockComment = true
			index++
			continue
		}
		if char != ' ' && char != '\t' && char != '\r' && char != '\n' {
			return false
		}
	}
	return true
}

func (s *Store) initializeDataset(ctx context.Context) error {
	settings := model.DefaultWorkSettings()
	workdays, err := json.Marshal(settings.Workdays)
	if err != nil {
		return err
	}
	now := time.Now().UTC()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx,
		"INSERT IGNORE INTO datasets(dataset_id, restore_version, created_at) VALUES (?, 1, ?)",
		s.datasetID, now); err != nil {
		return fmt.Errorf("initialize dataset: %w", err)
	}
	if _, err := tx.ExecContext(ctx,
		"INSERT IGNORE INTO sync_state(dataset_id, last_seq, updated_at) VALUES (?, 0, ?)",
		s.datasetID, now); err != nil {
		return fmt.Errorf("initialize sync state: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT IGNORE INTO work_settings
		  (dataset_id, workdays, time_zone, work_start, work_end, reminder_lead_minutes, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		s.datasetID, workdays, settings.TimeZone, settings.WorkStart, settings.WorkEnd,
		settings.ReminderLeadMinutes, now); err != nil {
		return fmt.Errorf("initialize work settings: %w", err)
	}
	return tx.Commit()
}

func (s *Store) Status(ctx context.Context) (model.Status, error) {
	var status model.Status
	err := s.db.QueryRowContext(ctx, `
		SELECT d.dataset_id, d.restore_version, s.last_seq
		FROM datasets d JOIN sync_state s ON s.dataset_id = d.dataset_id
		WHERE d.dataset_id = ?`, s.datasetID).
		Scan(&status.DatasetID, &status.RestoreVersion, &status.Cursor)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return model.Status{}, model.ErrNotFound
		}
		return model.Status{}, err
	}
	status.ProtocolVersion = 1
	status.ServerTime = time.Now().UTC()
	return status, nil
}

const taskColumns = `task_id, title, title_source, created_on, due_date, due_time,
	priority, completed, completed_at, updated_at`

type rowScanner interface {
	Scan(...any) error
}

func scanTask(scanner rowScanner) (model.Task, error) {
	var task model.Task
	var createdOn, dueDate time.Time
	var dueTime sql.NullString
	var completedAt sql.NullTime
	var completed bool
	err := scanner.Scan(
		&task.TaskID, &task.Title, &task.TitleSource, &createdOn, &dueDate, &dueTime,
		&task.Priority, &completed, &completedAt, &task.UpdatedAt,
	)
	if err != nil {
		return model.Task{}, err
	}
	task.CreatedOn = createdOn.Format("2006-01-02")
	task.DueDate = dueDate.Format("2006-01-02")
	if dueTime.Valid {
		value := dueTime.String
		task.DueTime = &value
	}
	task.Completed = completed
	if completedAt.Valid {
		value := completedAt.Time.UTC()
		task.CompletedAt = &value
	}
	task.UpdatedAt = task.UpdatedAt.UTC()
	return task, nil
}

func (s *Store) taskTx(ctx context.Context, tx *sql.Tx, taskID string, lock bool) (model.Task, error) {
	query := "SELECT " + taskColumns + " FROM tasks WHERE dataset_id = ? AND task_id = ?"
	if lock {
		query += " FOR UPDATE"
	}
	task, err := scanTask(tx.QueryRowContext(ctx, query, s.datasetID, taskID))
	if errors.Is(err, sql.ErrNoRows) {
		return model.Task{}, model.ErrNotFound
	}
	return task, err
}

func (s *Store) insertTaskTx(ctx context.Context, tx *sql.Tx, task model.Task) error {
	var dueTime any
	if task.DueTime != nil {
		dueTime = *task.DueTime
	}
	var completedAt any
	if task.CompletedAt != nil {
		completedAt = task.CompletedAt.UTC()
	}
	now := time.Now().UTC()
	_, err := tx.ExecContext(ctx, `
		INSERT INTO tasks
		  (dataset_id, task_id, title, title_source, created_on, due_date, due_time,
		   priority, completed, completed_at, updated_at, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		s.datasetID, task.TaskID, task.Title, task.TitleSource, task.CreatedOn, task.DueDate,
		dueTime, task.Priority, task.Completed, completedAt, task.UpdatedAt, now)
	if err != nil {
		if isDuplicate(err) {
			return fmt.Errorf("%w: task already exists", model.ErrConflict)
		}
		return err
	}
	return nil
}

func (s *Store) updateTaskTx(ctx context.Context, tx *sql.Tx, task model.Task) error {
	var dueTime any
	if task.DueTime != nil {
		dueTime = *task.DueTime
	}
	var completedAt any
	if task.CompletedAt != nil {
		completedAt = task.CompletedAt.UTC()
	}
	_, err := tx.ExecContext(ctx, `
		UPDATE tasks SET title = ?, title_source = ?, created_on = ?, due_date = ?, due_time = ?,
		  priority = ?, completed = ?, completed_at = ?, updated_at = ?
		WHERE dataset_id = ? AND task_id = ?`,
		task.Title, task.TitleSource, task.CreatedOn, task.DueDate, dueTime,
		task.Priority, task.Completed, completedAt, task.UpdatedAt, s.datasetID, task.TaskID)
	return err
}

func applyPatch(task *model.Task, patch model.TaskPatch, now time.Time) {
	if patch.Title != nil {
		task.Title = *patch.Title
	}
	if patch.TitleSource != nil {
		task.TitleSource = *patch.TitleSource
	}
	if patch.DueDate != nil {
		task.DueDate = *patch.DueDate
	}
	if patch.DueTimeSet {
		task.DueTime = patch.DueTime
	}
	if patch.Priority != nil {
		task.Priority = *patch.Priority
	}
	if patch.Completed != nil {
		if *patch.Completed {
			if !task.Completed || task.CompletedAt == nil {
				value := now.UTC()
				task.CompletedAt = &value
			}
		} else {
			task.CompletedAt = nil
		}
		task.Completed = *patch.Completed
	}
	task.UpdatedAt = now.UTC()
}

func (s *Store) ApplyOperation(ctx context.Context, op model.Operation, bodyHash string) (model.OperationReceipt, error) {
	if receipt, found, err := s.loadReceipt(ctx, op.OpID, bodyHash); err != nil || found {
		return receipt, err
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return model.OperationReceipt{}, err
	}
	defer tx.Rollback()

	lastSeq, err := lockSyncState(ctx, tx, s.datasetID)
	if err != nil {
		return model.OperationReceipt{}, err
	}
	if receipt, found, err := loadReceiptTx(ctx, tx, s.datasetID, op.OpID, bodyHash); err != nil {
		return model.OperationReceipt{}, err
	} else if found {
		return receipt, tx.Commit()
	}

	now := time.Now().UTC()
	receipt := model.OperationReceipt{OpID: op.OpID}
	var change model.Change
	switch op.Type {
	case "task.create":
		task := *op.Task
		if err := s.insertTaskTx(ctx, tx, task); err != nil {
			return model.OperationReceipt{}, err
		}
		receipt.Task = &task
		change = model.Change{Type: "task.created", Task: &task}
	case "task.update":
		task, err := s.taskTx(ctx, tx, op.TaskID, true)
		created := false
		if errors.Is(err, model.ErrNotFound) {
			if op.Fallback == nil {
				return model.OperationReceipt{}, fmt.Errorf("%w: task does not exist", model.ErrConflict)
			}
			task = *op.Fallback
			task.UpdatedAt = now
			if task.Completed && task.CompletedAt == nil {
				value := now
				task.CompletedAt = &value
			}
			if !task.Completed {
				task.CompletedAt = nil
			}
			if err := s.insertTaskTx(ctx, tx, task); err != nil {
				return model.OperationReceipt{}, err
			}
			created = true
		} else if err != nil {
			return model.OperationReceipt{}, err
		}
		applyPatch(&task, *op.Set, now)
		if err := s.updateTaskTx(ctx, tx, task); err != nil {
			return model.OperationReceipt{}, err
		}
		receipt.Task = &task
		changeType := "task.updated"
		if created {
			changeType = "task.created"
		}
		change = model.Change{Type: changeType, Task: &task}
	case "settings.update":
		settings := *op.Settings
		workdays, err := json.Marshal(settings.Workdays)
		if err != nil {
			return model.OperationReceipt{}, err
		}
		_, err = tx.ExecContext(ctx, `
			INSERT INTO work_settings
			  (dataset_id, workdays, time_zone, work_start, work_end, reminder_lead_minutes, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			ON DUPLICATE KEY UPDATE
			  workdays = VALUES(workdays), time_zone = VALUES(time_zone),
			  work_start = VALUES(work_start), work_end = VALUES(work_end),
			  reminder_lead_minutes = VALUES(reminder_lead_minutes), updated_at = VALUES(updated_at)`,
			s.datasetID, workdays, settings.TimeZone, settings.WorkStart, settings.WorkEnd,
			settings.ReminderLeadMinutes, now)
		if err != nil {
			return model.OperationReceipt{}, err
		}
		receipt.Settings = &settings
		change = model.Change{Type: "settings.updated", Settings: &settings}
	default:
		return model.OperationReceipt{}, fmt.Errorf("%w: unsupported operation", model.ErrInvalid)
	}

	lastSeq++
	change.Seq = lastSeq
	receipt.Cursor = lastSeq
	if err := s.writeChangeTx(ctx, tx, change); err != nil {
		return model.OperationReceipt{}, err
	}
	if _, err := tx.ExecContext(ctx,
		"UPDATE sync_state SET last_seq = ?, updated_at = ? WHERE dataset_id = ?",
		lastSeq, now, s.datasetID); err != nil {
		return model.OperationReceipt{}, err
	}
	receiptJSON, err := json.Marshal(receipt)
	if err != nil {
		return model.OperationReceipt{}, err
	}
	_, err = tx.ExecContext(ctx, `
		INSERT INTO operations
		  (op_id, dataset_id, device_id, request_sha256, receipt_json, applied_at)
		VALUES (?, ?, ?, ?, ?, ?)`,
		op.OpID, s.datasetID, op.DeviceID, bodyHash, receiptJSON, now)
	if err != nil {
		if isDuplicate(err) {
			_ = tx.Rollback()
			existing, found, lookupErr := s.loadReceipt(ctx, op.OpID, bodyHash)
			if lookupErr != nil {
				return model.OperationReceipt{}, lookupErr
			}
			if found {
				return existing, nil
			}
		}
		return model.OperationReceipt{}, err
	}
	if err := tx.Commit(); err != nil {
		return model.OperationReceipt{}, err
	}
	return receipt, nil
}

func lockSyncState(ctx context.Context, tx *sql.Tx, datasetID string) (int64, error) {
	var lastSeq int64
	err := tx.QueryRowContext(ctx,
		"SELECT last_seq FROM sync_state WHERE dataset_id = ? FOR UPDATE", datasetID).Scan(&lastSeq)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, model.ErrNotFound
	}
	return lastSeq, err
}

func (s *Store) loadReceipt(ctx context.Context, opID, bodyHash string) (model.OperationReceipt, bool, error) {
	var storedHash string
	var receiptJSON []byte
	err := s.db.QueryRowContext(ctx,
		"SELECT request_sha256, receipt_json FROM operations WHERE dataset_id = ? AND op_id = ?",
		s.datasetID, opID).
		Scan(&storedHash, &receiptJSON)
	if errors.Is(err, sql.ErrNoRows) {
		return model.OperationReceipt{}, false, nil
	}
	if err != nil {
		return model.OperationReceipt{}, false, err
	}
	return decodeReceipt(opID, bodyHash, storedHash, receiptJSON)
}

func loadReceiptTx(
	ctx context.Context,
	tx *sql.Tx,
	datasetID, opID, bodyHash string,
) (model.OperationReceipt, bool, error) {
	var storedHash string
	var receiptJSON []byte
	err := tx.QueryRowContext(ctx,
		"SELECT request_sha256, receipt_json FROM operations WHERE dataset_id = ? AND op_id = ?",
		datasetID, opID).
		Scan(&storedHash, &receiptJSON)
	if errors.Is(err, sql.ErrNoRows) {
		return model.OperationReceipt{}, false, nil
	}
	if err != nil {
		return model.OperationReceipt{}, false, err
	}
	return decodeReceipt(opID, bodyHash, storedHash, receiptJSON)
}

func decodeReceipt(opID, bodyHash, storedHash string, receiptJSON []byte) (model.OperationReceipt, bool, error) {
	if storedHash != bodyHash {
		return model.OperationReceipt{}, false, fmt.Errorf("%w: opId was already used with different content", model.ErrConflict)
	}
	var receipt model.OperationReceipt
	if err := json.Unmarshal(receiptJSON, &receipt); err != nil {
		return model.OperationReceipt{}, false, fmt.Errorf("decode operation receipt: %w", err)
	}
	return receipt, true, nil
}

func (s *Store) writeChangeTx(ctx context.Context, tx *sql.Tx, change model.Change) error {
	payload := change
	payload.Seq = 0
	encoded, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `
		INSERT INTO changes(dataset_id, seq, change_type, payload_json, created_at)
		VALUES (?, ?, ?, ?, ?)`,
		s.datasetID, change.Seq, change.Type, encoded, time.Now().UTC())
	return err
}

func (s *Store) Changes(ctx context.Context, after int64, limit int) (model.ChangesPage, error) {
	if limit < 1 {
		limit = 100
	}
	if limit > 200 {
		limit = 200
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT seq, change_type, payload_json
		FROM changes WHERE dataset_id = ? AND seq > ?
		ORDER BY seq ASC LIMIT ?`, s.datasetID, after, limit+1)
	if err != nil {
		return model.ChangesPage{}, err
	}
	defer rows.Close()

	changes := make([]model.Change, 0, limit)
	nextCursor := after
	hasMore := false
	for rows.Next() {
		if len(changes) == limit {
			hasMore = true
			break
		}
		var seq int64
		var changeType string
		var payload []byte
		if err := rows.Scan(&seq, &changeType, &payload); err != nil {
			return model.ChangesPage{}, err
		}
		var change model.Change
		if err := json.Unmarshal(payload, &change); err != nil {
			return model.ChangesPage{}, fmt.Errorf("decode change %d: %w", seq, err)
		}
		change.Seq = seq
		change.Type = changeType
		changes = append(changes, change)
		nextCursor = seq
	}
	if err := rows.Err(); err != nil {
		return model.ChangesPage{}, err
	}
	return model.ChangesPage{Changes: changes, NextCursor: nextCursor, HasMore: hasMore}, nil
}

func (s *Store) Snapshot(ctx context.Context) (model.Snapshot, error) {
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead})
	if err != nil {
		return model.Snapshot{}, err
	}
	defer tx.Rollback()

	var snapshot model.Snapshot
	if err := tx.QueryRowContext(ctx,
		"SELECT restore_version FROM datasets WHERE dataset_id = ?", s.datasetID).
		Scan(&snapshot.RestoreVersion); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return model.Snapshot{}, model.ErrNotFound
		}
		return model.Snapshot{}, err
	}
	tasks, err := queryTasks(ctx, tx, s.datasetID)
	if err != nil {
		return model.Snapshot{}, err
	}
	settings, err := querySettings(ctx, tx, s.datasetID)
	if err != nil {
		return model.Snapshot{}, err
	}
	attachments, err := queryAttachments(ctx, tx, s.datasetID)
	if err != nil {
		return model.Snapshot{}, err
	}
	if err := tx.QueryRowContext(ctx,
		"SELECT last_seq FROM sync_state WHERE dataset_id = ?", s.datasetID).
		Scan(&snapshot.Cursor); err != nil {
		return model.Snapshot{}, err
	}
	snapshot.Tasks = tasks
	snapshot.Settings = settings
	snapshot.Attachments = attachments
	return snapshot, nil
}

func queryTasks(ctx context.Context, tx *sql.Tx, datasetID string) ([]model.Task, error) {
	rows, err := tx.QueryContext(ctx,
		"SELECT "+taskColumns+" FROM tasks WHERE dataset_id = ? ORDER BY due_date, due_time, task_id",
		datasetID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	tasks := make([]model.Task, 0)
	for rows.Next() {
		task, err := scanTask(rows)
		if err != nil {
			return nil, err
		}
		tasks = append(tasks, task)
	}
	return tasks, rows.Err()
}

func querySettings(ctx context.Context, tx *sql.Tx, datasetID string) (model.WorkSettings, error) {
	var settings model.WorkSettings
	var workdays []byte
	err := tx.QueryRowContext(ctx, `
		SELECT workdays, time_zone, work_start, work_end, reminder_lead_minutes
		FROM work_settings WHERE dataset_id = ?`, datasetID).
		Scan(&workdays, &settings.TimeZone, &settings.WorkStart, &settings.WorkEnd, &settings.ReminderLeadMinutes)
	if errors.Is(err, sql.ErrNoRows) {
		return model.WorkSettings{}, model.ErrNotFound
	}
	if err != nil {
		return model.WorkSettings{}, err
	}
	if err := json.Unmarshal(workdays, &settings.Workdays); err != nil {
		return model.WorkSettings{}, fmt.Errorf("decode workdays: %w", err)
	}
	return settings, nil
}

const attachmentColumns = `attachment_id, task_id, kind, media_type, byte_size, sha256,
	storage_key, transcription_status, transcript, transcription_error, created_at`

func scanAttachment(scanner rowScanner) (model.Attachment, error) {
	var attachment model.Attachment
	var transcriptionStatus, transcript, transcriptionError sql.NullString
	err := scanner.Scan(
		&attachment.AttachmentID, &attachment.TaskID, &attachment.Kind, &attachment.MediaType,
		&attachment.ByteSize, &attachment.SHA256, &attachment.StorageKey,
		&transcriptionStatus, &transcript, &transcriptionError, &attachment.CreatedAt,
	)
	if err != nil {
		return model.Attachment{}, err
	}
	if transcriptionStatus.Valid {
		value := transcriptionStatus.String
		attachment.TranscriptionStatus = &value
	}
	if transcript.Valid {
		value := transcript.String
		attachment.Transcript = &value
	}
	if transcriptionError.Valid {
		value := transcriptionError.String
		attachment.TranscriptionError = &value
	}
	attachment.CreatedAt = attachment.CreatedAt.UTC()
	return attachment, nil
}

func queryAttachments(ctx context.Context, tx *sql.Tx, datasetID string) ([]model.Attachment, error) {
	rows, err := tx.QueryContext(ctx,
		"SELECT "+attachmentColumns+" FROM attachments WHERE dataset_id = ? ORDER BY created_at, attachment_id",
		datasetID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	attachments := make([]model.Attachment, 0)
	for rows.Next() {
		attachment, err := scanAttachment(rows)
		if err != nil {
			return nil, err
		}
		attachments = append(attachments, attachment)
	}
	return attachments, rows.Err()
}

func (s *Store) Attachment(ctx context.Context, attachmentID string) (model.Attachment, error) {
	attachment, err := scanAttachment(s.db.QueryRowContext(ctx,
		"SELECT "+attachmentColumns+" FROM attachments WHERE dataset_id = ? AND attachment_id = ?",
		s.datasetID, attachmentID))
	if errors.Is(err, sql.ErrNoRows) {
		return model.Attachment{}, model.ErrNotFound
	}
	return attachment, err
}

func (s *Store) PublishAttachment(ctx context.Context, attachment model.Attachment, storageKey string) (model.Attachment, bool, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return model.Attachment{}, false, err
	}
	defer tx.Rollback()
	lastSeq, err := lockSyncState(ctx, tx, s.datasetID)
	if err != nil {
		return model.Attachment{}, false, err
	}

	existing, err := scanAttachment(tx.QueryRowContext(ctx,
		"SELECT "+attachmentColumns+" FROM attachments WHERE dataset_id = ? AND attachment_id = ? FOR UPDATE",
		s.datasetID, attachment.AttachmentID))
	if err == nil {
		if existing.SHA256 != attachment.SHA256 || existing.TaskID != attachment.TaskID ||
			existing.Kind != attachment.Kind || existing.MediaType != attachment.MediaType ||
			existing.ByteSize != attachment.ByteSize {
			return model.Attachment{}, false, fmt.Errorf("%w: attachment id reused with different content", model.ErrConflict)
		}
		return existing, false, tx.Commit()
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return model.Attachment{}, false, err
	}
	var taskExists int
	if err := tx.QueryRowContext(ctx,
		"SELECT 1 FROM tasks WHERE dataset_id = ? AND task_id = ?", s.datasetID, attachment.TaskID).
		Scan(&taskExists); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return model.Attachment{}, false, fmt.Errorf("%w: task does not exist", model.ErrConflict)
		}
		return model.Attachment{}, false, err
	}

	now := time.Now().UTC()
	attachment.CreatedAt = now
	attachment.StorageKey = storageKey
	if attachment.Kind == model.AttachmentRecording {
		status := model.TranscriptionPending
		attachment.TranscriptionStatus = &status
	}
	var status, transcript, transcriptionError any
	if attachment.TranscriptionStatus != nil {
		status = *attachment.TranscriptionStatus
	}
	if attachment.Transcript != nil {
		transcript = *attachment.Transcript
	}
	if attachment.TranscriptionError != nil {
		transcriptionError = *attachment.TranscriptionError
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO attachments
		  (dataset_id, attachment_id, task_id, kind, media_type, byte_size, sha256, storage_key,
		   transcription_status, transcript, transcription_error, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		s.datasetID, attachment.AttachmentID, attachment.TaskID, attachment.Kind, attachment.MediaType,
		attachment.ByteSize, attachment.SHA256, attachment.StorageKey,
		status, transcript, transcriptionError, now, now); err != nil {
		if isDuplicate(err) {
			return model.Attachment{}, false, fmt.Errorf("%w: attachment already exists", model.ErrConflict)
		}
		return model.Attachment{}, false, err
	}
	if attachment.Kind == model.AttachmentRecording {
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO transcription_jobs
			  (dataset_id, attachment_id, status, attempts, available_at, updated_at)
			VALUES (?, ?, 'pending', 0, ?, ?)`,
			s.datasetID, attachment.AttachmentID, now, now); err != nil {
			return model.Attachment{}, false, err
		}
	}
	lastSeq++
	change := model.Change{Seq: lastSeq, Type: "attachment.updated", Attachment: &attachment}
	if err := s.writeChangeTx(ctx, tx, change); err != nil {
		return model.Attachment{}, false, err
	}
	if _, err := tx.ExecContext(ctx,
		"UPDATE sync_state SET last_seq = ?, updated_at = ? WHERE dataset_id = ?",
		lastSeq, now, s.datasetID); err != nil {
		return model.Attachment{}, false, err
	}
	if err := tx.Commit(); err != nil {
		return model.Attachment{}, false, err
	}
	return attachment, true, nil
}

func (s *Store) RetryTranscription(ctx context.Context, attachmentID string) (model.Attachment, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return model.Attachment{}, err
	}
	defer tx.Rollback()
	lastSeq, err := lockSyncState(ctx, tx, s.datasetID)
	if err != nil {
		return model.Attachment{}, err
	}
	attachment, err := scanAttachment(tx.QueryRowContext(ctx,
		"SELECT "+attachmentColumns+" FROM attachments WHERE dataset_id = ? AND attachment_id = ? FOR UPDATE",
		s.datasetID, attachmentID))
	if errors.Is(err, sql.ErrNoRows) {
		return model.Attachment{}, model.ErrNotFound
	}
	if err != nil {
		return model.Attachment{}, err
	}
	if attachment.Kind != model.AttachmentRecording {
		return model.Attachment{}, fmt.Errorf("%w: attachment is not a recording", model.ErrConflict)
	}
	if attachment.TranscriptionStatus != nil && *attachment.TranscriptionStatus != model.TranscriptionFailed {
		return model.Attachment{}, fmt.Errorf("%w: only failed transcriptions can be retried", model.ErrConflict)
	}
	now := time.Now().UTC()
	status := model.TranscriptionPending
	if _, err := tx.ExecContext(ctx, `
		UPDATE attachments SET transcription_status = ?, transcription_error = NULL, updated_at = ?
		WHERE dataset_id = ? AND attachment_id = ?`,
		status, now, s.datasetID, attachmentID); err != nil {
		return model.Attachment{}, err
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO transcription_jobs
		  (dataset_id, attachment_id, status, attempts, available_at, claimed_at, last_error, updated_at)
		VALUES (?, ?, 'pending', 0, ?, NULL, NULL, ?)
		ON DUPLICATE KEY UPDATE status = 'pending', attempts = 0, available_at = VALUES(available_at),
		  claimed_at = NULL, last_error = NULL, updated_at = VALUES(updated_at)`,
		s.datasetID, attachmentID, now, now); err != nil {
		return model.Attachment{}, err
	}
	attachment.TranscriptionStatus = &status
	attachment.TranscriptionError = nil
	lastSeq++
	change := model.Change{Seq: lastSeq, Type: "attachment.updated", Attachment: &attachment}
	if err := s.writeChangeTx(ctx, tx, change); err != nil {
		return model.Attachment{}, err
	}
	if _, err := tx.ExecContext(ctx,
		"UPDATE sync_state SET last_seq = ?, updated_at = ? WHERE dataset_id = ?",
		lastSeq, now, s.datasetID); err != nil {
		return model.Attachment{}, err
	}
	if err := tx.Commit(); err != nil {
		return model.Attachment{}, err
	}
	return attachment, nil
}

func (s *Store) ResetProcessingJobs(ctx context.Context) error {
	now := time.Now().UTC()
	_, err := s.db.ExecContext(ctx, `
		UPDATE transcription_jobs SET status = 'pending', claimed_at = NULL,
		  available_at = ?, updated_at = ?
		WHERE dataset_id = ? AND status = 'processing'`, now, now, s.datasetID)
	return err
}

func (s *Store) ClaimTranscription(ctx context.Context) (*model.TranscriptionJob, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	now := time.Now().UTC()
	var job model.TranscriptionJob
	err = tx.QueryRowContext(ctx, `
		SELECT j.attachment_id, a.task_id, a.storage_key, a.media_type, j.attempts
		FROM transcription_jobs j
		JOIN attachments a ON a.dataset_id = j.dataset_id AND a.attachment_id = j.attachment_id
		WHERE j.dataset_id = ? AND j.status = 'pending' AND j.available_at <= ?
		ORDER BY j.available_at, j.attachment_id
		LIMIT 1 FOR UPDATE SKIP LOCKED`, s.datasetID, now).
		Scan(&job.AttachmentID, &job.TaskID, &job.StorageKey, &job.MediaType, &job.Attempts)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, model.ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE transcription_jobs SET status = 'processing', attempts = attempts + 1,
		  claimed_at = ?, updated_at = ?
		WHERE dataset_id = ? AND attachment_id = ?`,
		now, now, s.datasetID, job.AttachmentID); err != nil {
		return nil, err
	}
	job.Attempts++
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return &job, nil
}

func (s *Store) FinishTranscription(
	ctx context.Context,
	attachmentID string,
	transcript *string,
	errorMessage *string,
) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	lastSeq, err := lockSyncState(ctx, tx, s.datasetID)
	if err != nil {
		return err
	}
	attachment, err := scanAttachment(tx.QueryRowContext(ctx,
		"SELECT "+attachmentColumns+" FROM attachments WHERE dataset_id = ? AND attachment_id = ? FOR UPDATE",
		s.datasetID, attachmentID))
	if errors.Is(err, sql.ErrNoRows) {
		return model.ErrNotFound
	}
	if err != nil {
		return err
	}
	now := time.Now().UTC()
	if errorMessage != nil {
		status := model.TranscriptionFailed
		attachment.TranscriptionStatus = &status
		attachment.TranscriptionError = errorMessage
		if _, err := tx.ExecContext(ctx, `
			UPDATE attachments SET transcription_status = ?, transcription_error = ?, updated_at = ?
			WHERE dataset_id = ? AND attachment_id = ?`,
			status, *errorMessage, now, s.datasetID, attachmentID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `
			UPDATE transcription_jobs SET status = 'failed', last_error = ?, updated_at = ?
			WHERE dataset_id = ? AND attachment_id = ?`,
			*errorMessage, now, s.datasetID, attachmentID); err != nil {
			return err
		}
		lastSeq++
		change := model.Change{Seq: lastSeq, Type: "attachment.updated", Attachment: &attachment}
		if err := s.writeChangeTx(ctx, tx, change); err != nil {
			return err
		}
	} else {
		if transcript == nil || strings.TrimSpace(*transcript) == "" {
			return fmt.Errorf("%w: transcription is empty", model.ErrInvalid)
		}
		text := strings.TrimSpace(*transcript)
		status := model.TranscriptionComplete
		attachment.TranscriptionStatus = &status
		attachment.Transcript = &text
		attachment.TranscriptionError = nil
		if _, err := tx.ExecContext(ctx, `
			UPDATE attachments SET transcription_status = ?, transcript = ?,
			  transcription_error = NULL, updated_at = ?
			WHERE dataset_id = ? AND attachment_id = ?`,
			status, text, now, s.datasetID, attachmentID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `
			UPDATE transcription_jobs SET status = 'complete', last_error = NULL, updated_at = ?
			WHERE dataset_id = ? AND attachment_id = ?`,
			now, s.datasetID, attachmentID); err != nil {
			return err
		}
		lastSeq++
		change := model.Change{Seq: lastSeq, Type: "attachment.updated", Attachment: &attachment}
		if err := s.writeChangeTx(ctx, tx, change); err != nil {
			return err
		}

		task, err := s.taskTx(ctx, tx, attachment.TaskID, true)
		if err == nil && task.TitleSource == model.TitleSourceVoice {
			task.Title = text
			task.TitleSource = model.TitleSourceManual
			task.UpdatedAt = now
			if err := s.updateTaskTx(ctx, tx, task); err != nil {
				return err
			}
			lastSeq++
			taskChange := model.Change{Seq: lastSeq, Type: "task.updated", Task: &task}
			if err := s.writeChangeTx(ctx, tx, taskChange); err != nil {
				return err
			}
		} else if err != nil && !errors.Is(err, model.ErrNotFound) {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx,
		"UPDATE sync_state SET last_seq = ?, updated_at = ? WHERE dataset_id = ?",
		lastSeq, now, s.datasetID); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) BackupInfo(ctx context.Context) (model.BackupInfo, error) {
	var info model.BackupInfo
	info.DatasetID = s.datasetID
	if err := s.db.QueryRowContext(ctx,
		"SELECT restore_version FROM datasets WHERE dataset_id = ?", s.datasetID).
		Scan(&info.RestoreVersion); err != nil {
		return model.BackupInfo{}, err
	}
	if err := s.db.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM tasks WHERE dataset_id = ?", s.datasetID).
		Scan(&info.TaskCount); err != nil {
		return model.BackupInfo{}, err
	}
	rows, err := s.db.QueryContext(ctx,
		"SELECT "+attachmentColumns+" FROM attachments WHERE dataset_id = ? ORDER BY attachment_id",
		s.datasetID)
	if err != nil {
		return model.BackupInfo{}, err
	}
	defer rows.Close()
	for rows.Next() {
		attachment, err := scanAttachment(rows)
		if err != nil {
			return model.BackupInfo{}, err
		}
		info.Attachments = append(info.Attachments, attachment)
	}
	return info, rows.Err()
}

func (s *Store) IncrementRestoreVersion(ctx context.Context) (int64, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	var version int64
	if err := tx.QueryRowContext(ctx,
		"SELECT restore_version FROM datasets WHERE dataset_id = ? FOR UPDATE", s.datasetID).
		Scan(&version); err != nil {
		return 0, err
	}
	version++
	if _, err := tx.ExecContext(ctx,
		"UPDATE datasets SET restore_version = ? WHERE dataset_id = ?", version, s.datasetID); err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return version, nil
}

func (s *Store) PingMySQL(ctx context.Context) (string, error) {
	var version string
	if err := s.db.QueryRowContext(ctx, "SELECT VERSION()").Scan(&version); err != nil {
		return "", err
	}
	return version, nil
}

func (s *Store) DatabaseIsEmpty(ctx context.Context) (bool, error) {
	var count int64
	if err := s.db.QueryRowContext(ctx, `
		SELECT COUNT(*) FROM information_schema.tables
		WHERE table_schema = DATABASE()`).Scan(&count); err != nil {
		return false, err
	}
	return count == 0, nil
}

func (s *Store) DropAllTables(ctx context.Context) error {
	conn, err := s.db.Conn(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()

	rows, err := conn.QueryContext(ctx, `
		SELECT table_name FROM information_schema.tables
		WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
		ORDER BY table_name`)
	if err != nil {
		return err
	}
	var tables []string
	for rows.Next() {
		var table string
		if err := rows.Scan(&table); err != nil {
			rows.Close()
			return err
		}
		tables = append(tables, table)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return err
	}
	if err := rows.Close(); err != nil {
		return err
	}
	if len(tables) == 0 {
		return nil
	}
	if _, err := conn.ExecContext(ctx, "SET FOREIGN_KEY_CHECKS = 0"); err != nil {
		return err
	}
	defer func() {
		_, _ = conn.ExecContext(context.Background(), "SET FOREIGN_KEY_CHECKS = 1")
	}()
	for _, table := range tables {
		quoted := "`" + strings.ReplaceAll(table, "`", "``") + "`"
		if _, err := conn.ExecContext(ctx, "DROP TABLE IF EXISTS "+quoted); err != nil {
			return fmt.Errorf("drop table %s: %w", table, err)
		}
	}
	return nil
}

func isDuplicate(err error) bool {
	var mysqlErr *driver.MySQLError
	return errors.As(err, &mysqlErr) && mysqlErr.Number == 1062
}
