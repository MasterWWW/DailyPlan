package transcribe

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/MasterWWW/DailyPlan/server/internal/config"
	"github.com/MasterWWW/DailyPlan/server/internal/model"
	"github.com/MasterWWW/DailyPlan/server/internal/storage"
	"github.com/MasterWWW/DailyPlan/server/internal/store"
)

type Worker struct {
	store       store.Store
	attachments *storage.Store
	cfg         config.Config
	logger      *log.Logger
}

func New(dataStore store.Store, attachments *storage.Store, cfg config.Config, logger *log.Logger) *Worker {
	return &Worker{store: dataStore, attachments: attachments, cfg: cfg, logger: logger}
}

func (w *Worker) Run(ctx context.Context) {
	if !w.cfg.TranscriptionEnabled() {
		w.logger.Printf("transcription worker disabled: WHISPER_BIN and WHISPER_MODEL are not both configured")
		return
	}
	if err := w.store.ResetProcessingJobs(ctx); err != nil {
		w.logger.Printf("reset interrupted transcription jobs: %v", err)
	}
	w.logger.Printf("transcription worker started with model %s", w.cfg.WhisperModelLabel)
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		w.drain(ctx)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (w *Worker) drain(ctx context.Context) {
	for {
		job, err := w.store.ClaimTranscription(ctx)
		if errors.Is(err, model.ErrNotFound) {
			return
		}
		if err != nil {
			if ctx.Err() == nil {
				w.logger.Printf("claim transcription job: %v", err)
			}
			return
		}
		w.process(ctx, job)
	}
}

func (w *Worker) process(ctx context.Context, job *model.TranscriptionJob) {
	path, err := w.attachments.Resolve(job.StorageKey)
	if err == nil {
		var transcript string
		transcript, err = w.transcribe(ctx, path)
		if err == nil {
			text := strings.TrimSpace(transcript)
			if text == "" {
				err = fmt.Errorf("whisper returned an empty transcript")
			} else {
				if finishErr := w.store.FinishTranscription(ctx, job.AttachmentID, &text, nil); finishErr != nil {
					w.logger.Printf("save transcription %s: %v", job.AttachmentID, finishErr)
				} else {
					w.logger.Printf("transcribed attachment %s", job.AttachmentID)
				}
				return
			}
		}
	}
	message := truncateError(err.Error(), 2000)
	w.logger.Printf("transcription failed for %s (attempt %d): %s", job.AttachmentID, job.Attempts, message)
	if finishErr := w.store.FinishTranscription(ctx, job.AttachmentID, nil, &message); finishErr != nil {
		w.logger.Printf("record transcription failure %s: %v", job.AttachmentID, finishErr)
	}
}

func (w *Worker) transcribe(parent context.Context, audioPath string) (string, error) {
	if !strings.HasSuffix(strings.ToLower(audioPath), ".wav") {
		return "", fmt.Errorf("recording is not a WAV file")
	}
	tempDir, err := os.MkdirTemp("", "dailyplan-whisper-*")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(tempDir)
	outputBase := filepath.Join(tempDir, "transcript")
	ctx, cancel := context.WithTimeout(parent, w.cfg.WhisperTimeout)
	defer cancel()
	command := exec.CommandContext(ctx, w.cfg.WhisperBin,
		"-m", w.cfg.WhisperModel,
		"-f", audioPath,
		"-l", w.cfg.WhisperLanguage,
		"-otxt",
		"-of", outputBase,
		"-nt",
	)
	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr
	if err := command.Run(); err != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return "", fmt.Errorf("whisper timed out after %s", w.cfg.WhisperTimeout)
		}
		detail := strings.TrimSpace(stderr.String())
		if detail == "" {
			detail = strings.TrimSpace(stdout.String())
		}
		return "", fmt.Errorf("whisper failed: %w: %s", err, truncateError(detail, 1200))
	}
	transcriptPath := outputBase + ".txt"
	content, err := os.ReadFile(transcriptPath)
	if err != nil {
		return "", fmt.Errorf("read whisper transcript: %w", err)
	}
	return string(content), nil
}

func truncateError(value string, limit int) string {
	value = strings.TrimSpace(value)
	if len(value) <= limit {
		return value
	}
	return value[:limit] + "…"
}
