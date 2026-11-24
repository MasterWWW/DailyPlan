package backup

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	driver "github.com/go-sql-driver/mysql"

	"github.com/MasterWWW/DailyPlan/server/internal/config"
	"github.com/MasterWWW/DailyPlan/server/internal/storage"
	"github.com/MasterWWW/DailyPlan/server/internal/store"
)

const (
	FormatVersion   = 1
	SchemaVersion   = 1
	manifestName    = "manifest.json"
	databaseName    = "database.sql"
	attachmentsDir  = "attachments"
	rollbackTimeout = 30 * time.Second
)

type Manifest struct {
	FormatVersion  int                  `json:"formatVersion"`
	SchemaVersion  int                  `json:"schemaVersion"`
	CreatedAt      time.Time            `json:"createdAt"`
	Database       DatabaseInfo         `json:"database"`
	DatasetID      string               `json:"datasetId"`
	RestoreVersion int64                `json:"restoreVersion"`
	TaskCount      int64                `json:"taskCount"`
	DatabaseSHA256 string               `json:"databaseSha256"`
	Transcription  TranscriptionInfo    `json:"transcription"`
	Attachments    []ManifestAttachment `json:"attachments"`
}

type DatabaseInfo struct {
	Type    string `json:"type"`
	Version string `json:"version"`
}

type TranscriptionInfo struct {
	ModelVersion string `json:"modelVersion"`
	ModelSHA256  string `json:"modelSha256,omitempty"`
}

type ManifestAttachment struct {
	AttachmentID string `json:"attachmentId"`
	StorageKey   string `json:"storageKey"`
	SHA256       string `json:"sha256"`
	ByteSize     int64  `json:"byteSize"`
}

func Export(ctx context.Context, cfg config.Config, dataStore store.Store, outDir string) error {
	if outDir == "" {
		return fmt.Errorf("backup output directory is required")
	}
	if _, err := os.Stat(outDir); err == nil {
		return fmt.Errorf("backup output directory already exists: %s", outDir)
	} else if !os.IsNotExist(err) {
		return err
	}
	parent := filepath.Dir(outDir)
	if err := os.MkdirAll(parent, 0o750); err != nil {
		return err
	}
	tempDir, err := os.MkdirTemp(parent, "."+filepath.Base(outDir)+".tmp-*")
	if err != nil {
		return err
	}
	committed := false
	defer func() {
		if !committed {
			_ = os.RemoveAll(tempDir)
		}
	}()
	if err := os.MkdirAll(filepath.Join(tempDir, attachmentsDir), 0o750); err != nil {
		return err
	}

	info, err := dataStore.BackupInfo(ctx)
	if err != nil {
		return fmt.Errorf("read backup metadata: %w", err)
	}
	mysqlVersion, err := dataStore.PingMySQL(ctx)
	if err != nil {
		return fmt.Errorf("read MySQL version: %w", err)
	}
	databasePath := filepath.Join(tempDir, databaseName)
	if err := runMysqldump(ctx, cfg.MySQLDSN, databasePath); err != nil {
		return err
	}
	databaseHash, err := hashFile(databasePath)
	if err != nil {
		return err
	}

	sourceStorage, err := storage.Open(cfg.AttachmentsDir)
	if err != nil {
		return err
	}
	manifestAttachments := make([]ManifestAttachment, 0, len(info.Attachments))
	for _, attachment := range info.Attachments {
		sourcePath, err := sourceStorage.Resolve(attachment.StorageKey)
		if err != nil {
			return fmt.Errorf("attachment %s: %w", attachment.AttachmentID, err)
		}
		destinationPath := filepath.Join(tempDir, attachmentsDir, filepath.FromSlash(attachment.StorageKey))
		size, digest, err := copyAndHash(sourcePath, destinationPath)
		if err != nil {
			return fmt.Errorf("copy attachment %s: %w", attachment.AttachmentID, err)
		}
		if size != attachment.ByteSize {
			return fmt.Errorf("attachment %s size mismatch: database=%d file=%d", attachment.AttachmentID, attachment.ByteSize, size)
		}
		if digest != attachment.SHA256 {
			return fmt.Errorf("attachment %s hash mismatch: database=%s file=%s", attachment.AttachmentID, attachment.SHA256, digest)
		}
		manifestAttachments = append(manifestAttachments, ManifestAttachment{
			AttachmentID: attachment.AttachmentID,
			StorageKey:   attachment.StorageKey,
			SHA256:       digest,
			ByteSize:     size,
		})
	}

	modelHash := ""
	if cfg.WhisperModel != "" {
		if _, statErr := os.Stat(cfg.WhisperModel); statErr == nil {
			modelHash, err = hashFile(cfg.WhisperModel)
			if err != nil {
				return fmt.Errorf("hash whisper model: %w", err)
			}
		}
	}
	manifest := Manifest{
		FormatVersion:  FormatVersion,
		SchemaVersion:  SchemaVersion,
		CreatedAt:      time.Now().UTC(),
		Database:       DatabaseInfo{Type: "mysql", Version: mysqlVersion},
		DatasetID:      info.DatasetID,
		RestoreVersion: info.RestoreVersion,
		TaskCount:      info.TaskCount,
		DatabaseSHA256: databaseHash,
		Transcription: TranscriptionInfo{
			ModelVersion: cfg.WhisperModelLabel,
			ModelSHA256:  modelHash,
		},
		Attachments: manifestAttachments,
	}
	encoded, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		return err
	}
	encoded = append(encoded, '\n')
	if err := os.WriteFile(filepath.Join(tempDir, manifestName), encoded, 0o640); err != nil {
		return err
	}
	if err := syncTree(tempDir); err != nil {
		return err
	}
	if err := os.Rename(tempDir, outDir); err != nil {
		return fmt.Errorf("publish backup directory: %w", err)
	}
	committed = true
	if err := syncDir(parent); err != nil {
		return err
	}
	return nil
}

func Restore(ctx context.Context, cfg config.Config, dataStore store.Store, inDir string) (returnErr error) {
	manifestBytes, err := os.ReadFile(filepath.Join(inDir, manifestName))
	if err != nil {
		return fmt.Errorf("read manifest: %w", err)
	}
	var manifest Manifest
	if err := json.Unmarshal(manifestBytes, &manifest); err != nil {
		return fmt.Errorf("parse manifest: %w", err)
	}
	if manifest.FormatVersion != FormatVersion || manifest.SchemaVersion != SchemaVersion {
		return fmt.Errorf("unsupported backup format %d/schema %d", manifest.FormatVersion, manifest.SchemaVersion)
	}
	if manifest.Database.Type != "mysql" {
		return fmt.Errorf("unsupported database type %q", manifest.Database.Type)
	}
	databasePath := filepath.Join(inDir, databaseName)
	databaseHash, err := hashFile(databasePath)
	if err != nil {
		return err
	}
	if databaseHash != manifest.DatabaseSHA256 {
		return fmt.Errorf("database dump checksum mismatch")
	}
	if manifest.TaskCount < 0 {
		return fmt.Errorf("invalid task count")
	}
	targetStorage, err := storage.Open(cfg.AttachmentsDir)
	if err != nil {
		return err
	}
	if err := ensureDirectoryEmpty(targetStorage.Root()); err != nil {
		return err
	}
	stageDir := filepath.Join(targetStorage.Root(), ".dailyplan-restore-"+strconv.FormatInt(time.Now().UnixNano(), 10))
	if err := os.MkdirAll(stageDir, 0o750); err != nil {
		return err
	}
	defer os.RemoveAll(stageDir)
	importStarted := false
	defer func() {
		if returnErr == nil || !importStarted {
			return
		}
		if cleanupErr := rollbackRestore(dataStore, targetStorage); cleanupErr != nil {
			returnErr = errors.Join(returnErr, fmt.Errorf("roll back failed restore: %w", cleanupErr))
		}
	}()
	seen := make(map[string]bool)
	for _, attachment := range manifest.Attachments {
		if attachment.AttachmentID == "" || seen[attachment.StorageKey] {
			return fmt.Errorf("invalid or duplicate attachment entry %q", attachment.StorageKey)
		}
		seen[attachment.StorageKey] = true
		if strings.Contains(attachment.StorageKey, `\`) || filepath.IsAbs(attachment.StorageKey) {
			return fmt.Errorf("unsafe attachment path %q", attachment.StorageKey)
		}
		clean := filepath.Clean(attachment.StorageKey)
		if clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
			return fmt.Errorf("unsafe attachment path %q", attachment.StorageKey)
		}
		sourcePath := filepath.Join(inDir, attachmentsDir, clean)
		stagePath := filepath.Join(stageDir, clean)
		size, digest, err := copyAndHash(sourcePath, stagePath)
		if err != nil {
			return fmt.Errorf("verify attachment %s: %w", attachment.AttachmentID, err)
		}
		if size != attachment.ByteSize || digest != attachment.SHA256 {
			return fmt.Errorf("attachment %s (%s) checksum mismatch", attachment.AttachmentID, attachment.StorageKey)
		}
	}

	targetVersion, err := dataStore.PingMySQL(ctx)
	if err != nil {
		return fmt.Errorf("read target MySQL version: %w", err)
	}
	if !compatibleMySQL(manifest.Database.Version, targetVersion) {
		return fmt.Errorf("backup MySQL version %q is not compatible with target %q", manifest.Database.Version, targetVersion)
	}
	empty, err := dataStore.DatabaseIsEmpty(ctx)
	if err != nil {
		return err
	}
	if !empty {
		return fmt.Errorf("target database must be empty")
	}
	importStarted = true
	if err := runMySQLImport(ctx, cfg.MySQLDSN, databasePath); err != nil {
		return err
	}
	info, err := dataStore.BackupInfo(ctx)
	if err != nil {
		return fmt.Errorf("read restored metadata: %w", err)
	}
	if info.TaskCount != manifest.TaskCount || len(info.Attachments) != len(manifest.Attachments) {
		return fmt.Errorf("restored record counts do not match manifest")
	}
	if info.DatasetID != manifest.DatasetID {
		return fmt.Errorf("restored dataset id %q does not match manifest %q", info.DatasetID, manifest.DatasetID)
	}
	for i, attachment := range info.Attachments {
		expected := manifest.Attachments[i]
		if attachment.AttachmentID != expected.AttachmentID ||
			attachment.StorageKey != expected.StorageKey ||
			attachment.SHA256 != expected.SHA256 ||
			attachment.ByteSize != expected.ByteSize {
			return fmt.Errorf("restored attachment %s does not match manifest", attachment.AttachmentID)
		}
	}
	for _, attachment := range manifest.Attachments {
		targetPath, err := targetStorage.Resolve(attachment.StorageKey)
		if err != nil {
			return err
		}
		if err := os.MkdirAll(filepath.Dir(targetPath), 0o750); err != nil {
			return err
		}
		if err := os.Rename(filepath.Join(stageDir, filepath.FromSlash(attachment.StorageKey)), targetPath); err != nil {
			return fmt.Errorf("install attachment %s: %w", attachment.AttachmentID, err)
		}
	}
	if err := syncTree(targetStorage.Root()); err != nil {
		return err
	}
	version, err := dataStore.IncrementRestoreVersion(ctx)
	if err != nil {
		return fmt.Errorf("increment restore version: %w", err)
	}
	if version != manifest.RestoreVersion+1 {
		return fmt.Errorf("restore version is %d, expected %d", version, manifest.RestoreVersion+1)
	}
	return nil
}

type restoreRollbackStore interface {
	DropAllTables(context.Context) error
}

func rollbackRestore(dataStore restoreRollbackStore, targetStorage *storage.Store) error {
	var cleanupErrors []error
	if err := clearAttachmentTree(targetStorage.Root()); err != nil {
		cleanupErrors = append(cleanupErrors, fmt.Errorf("clear attachments: %w", err))
	}
	ctx, cancel := context.WithTimeout(context.Background(), rollbackTimeout)
	defer cancel()
	if err := dataStore.DropAllTables(ctx); err != nil {
		cleanupErrors = append(cleanupErrors, fmt.Errorf("drop database tables: %w", err))
	}
	return errors.Join(cleanupErrors...)
}

func clearAttachmentTree(root string) error {
	entries, err := os.ReadDir(root)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if entry.Name() == ".tmp" && entry.IsDir() {
			continue
		}
		if err := os.RemoveAll(filepath.Join(root, entry.Name())); err != nil {
			return err
		}
	}
	return syncDir(root)
}

func runMysqldump(ctx context.Context, dsn, destination string) error {
	parsed, err := driver.ParseDSN(dsn)
	if err != nil {
		return err
	}
	if parsed.DBName == "" {
		return fmt.Errorf("MySQL DSN must include a database name")
	}
	args := connectionArgs(parsed)
	args = append(args,
		"--single-transaction",
		"--skip-lock-tables",
		"--no-tablespaces",
		"--default-character-set=utf8mb4",
		"--triggers",
		"--events",
		"--routines",
		"--databases",
		parsed.DBName,
	)
	command := exec.CommandContext(ctx, "mysqldump", args...)
	command.Env = commandEnvironment(parsed.Passwd)
	file, err := os.OpenFile(destination, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o640)
	if err != nil {
		return err
	}
	defer file.Close()
	stderr := &strings.Builder{}
	command.Stdout = file
	command.Stderr = stderr
	if err := command.Run(); err != nil {
		return fmt.Errorf("mysqldump: %w: %s", err, strings.TrimSpace(stderr.String()))
	}
	if err := file.Sync(); err != nil {
		return err
	}
	return nil
}

func runMySQLImport(ctx context.Context, dsn, source string) error {
	parsed, err := driver.ParseDSN(dsn)
	if err != nil {
		return err
	}
	if parsed.DBName == "" {
		return fmt.Errorf("MySQL DSN must include a database name")
	}
	file, err := os.Open(source)
	if err != nil {
		return err
	}
	defer file.Close()
	args := connectionArgs(parsed)
	args = append(args, parsed.DBName)
	command := exec.CommandContext(ctx, "mysql", args...)
	command.Env = commandEnvironment(parsed.Passwd)
	stderr := &strings.Builder{}
	command.Stdin = file
	command.Stderr = stderr
	if err := command.Run(); err != nil {
		return fmt.Errorf("mysql import: %w: %s", err, strings.TrimSpace(stderr.String()))
	}
	return nil
}

func connectionArgs(cfg *driver.Config) []string {
	args := []string{"--default-character-set=utf8mb4", "--user=" + cfg.User}
	switch cfg.Net {
	case "unix":
		args = append(args, "--protocol=socket", "--socket="+cfg.Addr)
	default:
		host, port, found := strings.Cut(cfg.Addr, ":")
		if !found {
			host = cfg.Addr
			port = "3306"
		}
		args = append(args, "--protocol=tcp", "--host="+host, "--port="+port)
	}
	return args
}

func commandEnvironment(password string) []string {
	env := os.Environ()
	if password != "" {
		env = append(env, "MYSQL_PWD="+password)
	}
	return env
}

func compatibleMySQL(backup, target string) bool {
	return majorVersion(backup) != "" && majorVersion(backup) == majorVersion(target)
}

func majorVersion(version string) string {
	version = strings.TrimSpace(version)
	if cut := strings.IndexAny(version, ".-"); cut >= 0 {
		version = version[:cut]
	}
	if _, err := strconv.Atoi(version); err != nil {
		return ""
	}
	return version
}

func hashFile(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hasher := sha256.New()
	if _, err := io.Copy(hasher, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hasher.Sum(nil)), nil
}

func copyAndHash(source, destination string) (int64, string, error) {
	sourceFile, err := os.Open(source)
	if err != nil {
		return 0, "", err
	}
	defer sourceFile.Close()
	if err := os.MkdirAll(filepath.Dir(destination), 0o750); err != nil {
		return 0, "", err
	}
	destinationFile, err := os.OpenFile(destination, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o640)
	if err != nil {
		return 0, "", err
	}
	success := false
	defer func() {
		_ = destinationFile.Close()
		if !success {
			_ = os.Remove(destination)
		}
	}()
	hasher := sha256.New()
	size, err := io.Copy(io.MultiWriter(destinationFile, hasher), sourceFile)
	if err != nil {
		return 0, "", err
	}
	if err := destinationFile.Sync(); err != nil {
		return 0, "", err
	}
	success = true
	return size, hex.EncodeToString(hasher.Sum(nil)), nil
}

func ensureDirectoryEmpty(path string) error {
	entries, err := os.ReadDir(path)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if entry.Name() == ".tmp" && entry.IsDir() {
			continue
		}
		return fmt.Errorf("target attachment directory must be empty")
	}
	return nil
}

func syncTree(root string) error {
	return filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() {
			return syncDir(path)
		}
		file, err := os.Open(path)
		if err != nil {
			return err
		}
		defer file.Close()
		return file.Sync()
	})
}

func syncDir(path string) error {
	dir, err := os.Open(path)
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}
