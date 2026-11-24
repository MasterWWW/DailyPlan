package storage

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWriteArchiveAndResolve(t *testing.T) {
	store, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	temp, err := store.WriteTemp(strings.NewReader("hello"), 100)
	if err != nil {
		t.Fatal(err)
	}
	if temp.Size != 5 || temp.SHA256 != "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824" {
		t.Fatalf("unexpected temp metadata: %#v", temp)
	}
	key, err := store.Archive(temp, "attachment-1", "audio/wav")
	if err != nil {
		t.Fatal(err)
	}
	if key != "attachment-1.wav" {
		t.Fatalf("unexpected key %q", key)
	}
	file, err := store.Open(key)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		t.Fatal(err)
	}
	if info.Size() != 5 {
		t.Fatalf("unexpected archived size %d", info.Size())
	}
}

func TestWriteTempRejectsOversizedInput(t *testing.T) {
	store, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	temp, err := store.WriteTemp(strings.NewReader("0123456789"), 5)
	if !errors.Is(err, ErrTooLarge) {
		t.Fatalf("expected too-large error, got %v", err)
	}
	if temp.Path != "" {
		t.Fatalf("expected temp file to be cleaned, got %s", temp.Path)
	}
}

func TestResolveRejectsTraversal(t *testing.T) {
	store, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Resolve("../secret"); err == nil {
		t.Fatal("expected traversal to be rejected")
	}
	if _, err := store.Resolve(filepath.Join("sub", "..", "..", "secret")); err == nil {
		t.Fatal("expected normalized traversal to be rejected")
	}
}

func TestArchiveUsesAtomicRename(t *testing.T) {
	root := t.TempDir()
	store, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	temp, err := store.WriteTemp(strings.NewReader("image"), 100)
	if err != nil {
		t.Fatal(err)
	}
	key, err := store.Archive(temp, "image-1", "image/png")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(temp.Path); !os.IsNotExist(err) {
		t.Fatalf("expected temp file to be moved, stat error: %v", err)
	}
	if _, err := os.Stat(filepath.Join(root, key)); err != nil {
		t.Fatal(err)
	}
}

func TestMatchesDetectsMissingAndChangedFiles(t *testing.T) {
	store, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	temp, err := store.WriteTemp(strings.NewReader("image"), 100)
	if err != nil {
		t.Fatal(err)
	}
	key, err := store.Archive(temp, "image-2", "image/png")
	if err != nil {
		t.Fatal(err)
	}
	matches, err := store.Matches(key, 5, temp.SHA256)
	if err != nil || !matches {
		t.Fatalf("expected matching file, matches=%v err=%v", matches, err)
	}
	matches, err = store.Matches(key, 4, temp.SHA256)
	if err != nil || matches {
		t.Fatalf("expected size mismatch, matches=%v err=%v", matches, err)
	}
	matches, err = store.Matches(key, 5, strings.Repeat("0", 64))
	if err != nil || matches {
		t.Fatalf("expected digest mismatch, matches=%v err=%v", matches, err)
	}
	if err := os.Remove(filepath.Join(store.Root(), key)); err != nil {
		t.Fatal(err)
	}
	matches, err = store.Matches(key, 5, temp.SHA256)
	if err != nil || matches {
		t.Fatalf("expected missing file mismatch, matches=%v err=%v", matches, err)
	}
}
