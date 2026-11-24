package storage

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

type Store struct {
	root string
	tmp  string
}

type TempFile struct {
	Path   string
	Size   int64
	SHA256 string
}

func Open(root string) (*Store, error) {
	if err := os.MkdirAll(root, 0o750); err != nil {
		return nil, fmt.Errorf("create attachment directory: %w", err)
	}
	tmp := filepath.Join(root, ".tmp")
	if err := os.MkdirAll(tmp, 0o750); err != nil {
		return nil, fmt.Errorf("create attachment temp directory: %w", err)
	}
	return &Store{root: root, tmp: tmp}, nil
}

func (s *Store) WriteTemp(r io.Reader, maxBytes int64) (TempFile, error) {
	file, err := os.CreateTemp(s.tmp, "upload-*")
	if err != nil {
		return TempFile{}, fmt.Errorf("create temp upload: %w", err)
	}
	path := file.Name()
	success := false
	defer func() {
		_ = file.Close()
		if !success {
			_ = os.Remove(path)
		}
	}()

	hasher := sha256.New()
	written, err := io.Copy(io.MultiWriter(file, hasher), io.LimitReader(r, maxBytes+1))
	if err != nil {
		return TempFile{}, fmt.Errorf("write temp upload: %w", err)
	}
	if written > maxBytes {
		return TempFile{}, fmt.Errorf("upload exceeds %d bytes: %w", maxBytes, ErrTooLarge)
	}
	if err := file.Sync(); err != nil {
		return TempFile{}, fmt.Errorf("sync temp upload: %w", err)
	}
	if err := file.Close(); err != nil {
		return TempFile{}, fmt.Errorf("close temp upload: %w", err)
	}
	success = true
	return TempFile{
		Path:   path,
		Size:   written,
		SHA256: hex.EncodeToString(hasher.Sum(nil)),
	}, nil
}

var ErrTooLarge = fmt.Errorf("attachment too large")

func (s *Store) Archive(temp TempFile, attachmentID, mediaType string) (string, error) {
	extension, err := extensionFor(mediaType)
	if err != nil {
		return "", err
	}
	key := attachmentID + extension
	destination, err := s.Resolve(key)
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(destination), 0o750); err != nil {
		return "", fmt.Errorf("create attachment shard: %w", err)
	}
	if err := os.Rename(temp.Path, destination); err != nil {
		return "", fmt.Errorf("archive attachment: %w", err)
	}
	if err := syncDir(filepath.Dir(destination)); err != nil {
		return "", err
	}
	return key, nil
}

func (s *Store) Remove(key string) {
	path, err := s.Resolve(key)
	if err == nil {
		_ = os.Remove(path)
	}
}

func (s *Store) Matches(key string, size int64, digest string) (bool, error) {
	file, err := s.Open(key)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return false, nil
		}
		return false, err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return false, fmt.Errorf("stat attachment: %w", err)
	}
	if info.Size() != size {
		return false, nil
	}
	hasher := sha256.New()
	if _, err := io.Copy(hasher, file); err != nil {
		return false, fmt.Errorf("hash attachment: %w", err)
	}
	return hex.EncodeToString(hasher.Sum(nil)) == digest, nil
}

func (s *Store) Open(key string) (*os.File, error) {
	path, err := s.Resolve(key)
	if err != nil {
		return nil, err
	}
	file, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, os.ErrNotExist
		}
		return nil, fmt.Errorf("open attachment: %w", err)
	}
	return file, nil
}

func (s *Store) Resolve(key string) (string, error) {
	if key == "" || filepath.IsAbs(key) || strings.Contains(key, `\`) {
		return "", fmt.Errorf("invalid attachment key")
	}
	path := filepath.Join(s.root, filepath.Clean(key))
	relative, err := filepath.Rel(s.root, path)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("attachment key escapes root")
	}
	return path, nil
}

func (s *Store) Root() string {
	return s.root
}

func extensionFor(mediaType string) (string, error) {
	switch mediaType {
	case "image/png":
		return ".png", nil
	case "image/jpeg":
		return ".jpg", nil
	case "audio/wav":
		return ".wav", nil
	default:
		return "", fmt.Errorf("unsupported media type %q", mediaType)
	}
}

func syncDir(path string) error {
	dir, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open attachment directory for sync: %w", err)
	}
	defer dir.Close()
	if err := dir.Sync(); err != nil {
		return fmt.Errorf("sync attachment directory: %w", err)
	}
	return nil
}
