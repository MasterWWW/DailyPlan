package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/MasterWWW/DailyPlan/server/internal/api"
	"github.com/MasterWWW/DailyPlan/server/internal/backup"
	"github.com/MasterWWW/DailyPlan/server/internal/config"
	"github.com/MasterWWW/DailyPlan/server/internal/storage"
	mysqlstore "github.com/MasterWWW/DailyPlan/server/internal/store/mysql"
	"github.com/MasterWWW/DailyPlan/server/internal/transcribe"
)

func main() {
	logger := log.New(os.Stdout, "dailyplan ", log.Ldate|log.Ltime|log.LUTC)
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "serve":
		err = serve(logger, os.Args[2:])
	case "export":
		err = exportBackup(logger, os.Args[2:])
	case "restore":
		err = restoreBackup(logger, os.Args[2:])
	case "help", "-h", "--help":
		usage()
		return
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		logger.Printf("error: %v", err)
		os.Exit(1)
	}
}

func serve(logger *log.Logger, args []string) error {
	flags := flag.NewFlagSet("serve", flag.ContinueOnError)
	flags.SetOutput(os.Stderr)
	if err := flags.Parse(args); err != nil {
		return err
	}
	cfg, err := config.Load(true)
	if err != nil {
		return err
	}
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	dataStore, err := mysqlstore.Open(ctx, cfg.MySQLDSN, cfg.DatasetID)
	if err != nil {
		return err
	}
	defer dataStore.Close()
	attachments, err := storage.Open(cfg.AttachmentsDir)
	if err != nil {
		return err
	}
	if cfg.ReadOnly {
		logger.Printf("serving in READ-ONLY mode for migration; writes and transcription are paused")
	}
	if cfg.TranscriptionEnabled() && !cfg.ReadOnly {
		worker := transcribe.New(dataStore, attachments, cfg, logger)
		go worker.Run(ctx)
	}
	handler := api.New(dataStore, attachments, cfg, logger, cfg.TranscriptionEnabled())
	server := &http.Server{
		Addr:              cfg.Addr,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       2 * time.Minute,
		WriteTimeout:      0,
		MaxHeaderBytes:    1 << 20,
	}
	go func() {
		<-ctx.Done()
		shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
		defer shutdownCancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			logger.Printf("graceful shutdown: %v", err)
		}
	}()
	logger.Printf("API listening on %s (dataset %s, attachments %s)", cfg.Addr, cfg.DatasetID, cfg.AttachmentsDir)
	err = server.ListenAndServe()
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func exportBackup(logger *log.Logger, args []string) error {
	flags := flag.NewFlagSet("export", flag.ContinueOnError)
	flags.SetOutput(os.Stderr)
	outDir := flags.String("out", "", "new backup directory")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if *outDir == "" {
		return fmt.Errorf("--out is required")
	}
	absoluteOut, err := filepath.Abs(*outDir)
	if err != nil {
		return err
	}
	cfg, err := config.Load(false)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Hour)
	defer cancel()
	dataStore, err := mysqlstore.Open(ctx, cfg.MySQLDSN, cfg.DatasetID)
	if err != nil {
		return err
	}
	defer dataStore.Close()
	if err := backup.Export(ctx, cfg, dataStore, absoluteOut); err != nil {
		return err
	}
	logger.Printf("backup written to %s", absoluteOut)
	return nil
}

func restoreBackup(logger *log.Logger, args []string) error {
	flags := flag.NewFlagSet("restore", flag.ContinueOnError)
	flags.SetOutput(os.Stderr)
	inDir := flags.String("in", "", "backup directory produced by export")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if *inDir == "" {
		return fmt.Errorf("--in is required")
	}
	absoluteIn, err := filepath.Abs(*inDir)
	if err != nil {
		return err
	}
	cfg, err := config.Load(false)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Hour)
	defer cancel()
	dataStore, err := mysqlstore.OpenExisting(ctx, cfg.MySQLDSN, cfg.DatasetID)
	if err != nil {
		return err
	}
	defer dataStore.Close()
	if err := backup.Restore(ctx, cfg, dataStore, absoluteIn); err != nil {
		return err
	}
	logger.Printf("backup restored from %s", absoluteIn)
	return nil
}

func usage() {
	fmt.Fprintln(os.Stderr, `Usage:
  dailyplan serve
  dailyplan export --out /backups/dailyplan-YYYYMMDD-HHMMSS
  dailyplan restore --in /backups/dailyplan-YYYYMMDD-HHMMSS

Required environment:
  DAILYPLAN_MYSQL_DSN
  DAILYPLAN_API_TOKEN             serve only
  DAILYPLAN_ATTACHMENTS_DIR

Optional environment:
  DAILYPLAN_ADDR                  default :8787
  DAILYPLAN_DATASET_ID            default office-default
  DAILYPLAN_READ_ONLY             true during export/restore windows
  DAILYPLAN_MAX_UPLOAD_BYTES      default 104857600
  WHISPER_BIN, WHISPER_MODEL      enables recording transcription
  WHISPER_LANGUAGE                default zh
  WHISPER_MODEL_VERSION           recorded in backup manifest
  DAILYPLAN_WHISPER_TIMEOUT       default 15m`)
}
