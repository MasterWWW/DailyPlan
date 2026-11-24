.PHONY: test test-go test-go-integration test-rust test-mobile build-server build-desktop package-macos

test: test-go test-rust test-mobile

test-go:
	cd server && go test ./...

test-go-integration:
	@docker compose -p dailyplan-test -f deploy/docker-compose.test.yml up -d --wait && \
	( trap 'cd "$(CURDIR)" && docker compose -p dailyplan-test -f deploy/docker-compose.test.yml down -v --remove-orphans' EXIT; \
	  cd server && \
	  DAILYPLAN_TEST_MYSQL_DSN='dailyplan:dailyplan_test_password@tcp(127.0.0.1:13306)/dailyplan_test?parseTime=true&charset=utf8mb4&loc=UTC' \
	  go test ./... -count=1 )

test-rust:
	cd apps/desktop && pnpm install --frozen-lockfile && pnpm build
	cd apps/desktop/src-tauri && cargo test

test-mobile:
	cd apps/mobile && ./gradlew :shared:testDebugUnitTest

build-server:
	cd server && go build -o ../dist/dailyplan ./cmd/dailyplan

build-desktop:
	cd apps/desktop && pnpm install --frozen-lockfile && pnpm tauri build

package-macos:
	cd apps/desktop && pnpm install --frozen-lockfile && ./scripts/package-macos.sh

