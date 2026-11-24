package backup

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"github.com/MasterWWW/DailyPlan/server/internal/storage"
)

func TestClearAttachmentTreeKeepsTemporaryDirectory(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, ".tmp"), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, "ab"), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "ab", "attachment.png"), []byte("image"), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := clearAttachmentTree(root); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != ".tmp" {
		t.Fatalf("remaining entries = %#v, want only .tmp", entries)
	}
}

type rollbackProbe struct {
	contextErr  error
	hasDeadline bool
}

func (s *rollbackProbe) DropAllTables(ctx context.Context) error {
	s.contextErr = ctx.Err()
	_, s.hasDeadline = ctx.Deadline()
	return nil
}

func TestRollbackRestoreUsesIndependentDeadline(t *testing.T) {
	targetStorage, err := storage.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	store := &rollbackProbe{}
	if err := rollbackRestore(store, targetStorage); err != nil {
		t.Fatal(err)
	}
	if store.contextErr != nil {
		t.Fatalf("rollback context was already done: %v", store.contextErr)
	}
	if !store.hasDeadline {
		t.Fatal("rollback context has no deadline")
	}
}
