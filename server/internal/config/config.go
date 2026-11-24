package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Addr              string
	APIToken          string
	MySQLDSN          string
	DatasetID         string
	AttachmentsDir    string
	ReadOnly          bool
	MaxUploadBytes    int64
	ShutdownTimeout   time.Duration
	WhisperBin        string
	WhisperModel      string
	WhisperLanguage   string
	WhisperModelLabel string
	WhisperTimeout    time.Duration
}

func Load(requireServeValues bool) (Config, error) {
	cfg := Config{
		Addr:              env("DAILYPLAN_ADDR", ":8787"),
		APIToken:          strings.TrimSpace(os.Getenv("DAILYPLAN_API_TOKEN")),
		MySQLDSN:          strings.TrimSpace(os.Getenv("DAILYPLAN_MYSQL_DSN")),
		DatasetID:         env("DAILYPLAN_DATASET_ID", "office-default"),
		AttachmentsDir:    env("DAILYPLAN_ATTACHMENTS_DIR", "./data/attachments"),
		WhisperBin:        strings.TrimSpace(os.Getenv("WHISPER_BIN")),
		WhisperModel:      strings.TrimSpace(os.Getenv("WHISPER_MODEL")),
		WhisperLanguage:   env("WHISPER_LANGUAGE", "zh"),
		WhisperModelLabel: env("WHISPER_MODEL_VERSION", "whisper-base"),
		ShutdownTimeout:   15 * time.Second,
	}
	var err error
	cfg.ReadOnly, err = boolEnv("DAILYPLAN_READ_ONLY", false)
	if err != nil {
		return Config{}, err
	}
	cfg.MaxUploadBytes, err = intEnv("DAILYPLAN_MAX_UPLOAD_BYTES", 100*1024*1024)
	if err != nil {
		return Config{}, err
	}
	cfg.WhisperTimeout, err = durationEnv("DAILYPLAN_WHISPER_TIMEOUT", 15*time.Minute)
	if err != nil {
		return Config{}, err
	}
	if cfg.MySQLDSN == "" {
		return Config{}, fmt.Errorf("DAILYPLAN_MYSQL_DSN is required")
	}
	if cfg.DatasetID == "" {
		return Config{}, fmt.Errorf("DAILYPLAN_DATASET_ID must not be empty")
	}
	if cfg.MaxUploadBytes < 1 {
		return Config{}, fmt.Errorf("DAILYPLAN_MAX_UPLOAD_BYTES must be positive")
	}
	if requireServeValues && cfg.APIToken == "" {
		return Config{}, fmt.Errorf("DAILYPLAN_API_TOKEN is required")
	}
	absoluteAttachments, err := filepath.Abs(cfg.AttachmentsDir)
	if err != nil {
		return Config{}, fmt.Errorf("resolve attachment directory: %w", err)
	}
	cfg.AttachmentsDir = absoluteAttachments
	return cfg, nil
}

func (c Config) TranscriptionEnabled() bool {
	return c.WhisperBin != "" && c.WhisperModel != ""
}

func env(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func boolEnv(key string, fallback bool) (bool, error) {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return false, fmt.Errorf("%s: %w", key, err)
	}
	return parsed, nil
}

func intEnv(key string, fallback int64) (int64, error) {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("%s: %w", key, err)
	}
	return parsed, nil
}

func durationEnv(key string, fallback time.Duration) (time.Duration, error) {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback, nil
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("%s: %w", key, err)
	}
	return parsed, nil
}
