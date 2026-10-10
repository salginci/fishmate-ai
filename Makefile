# FishMate AI — developer entry points.
.DEFAULT_GOAL := help
SHELL := /bin/bash
BACKEND := backend
ANDROID := android
WEB := web
PY := $(BACKEND)/.venv/bin/python
VENV := $(BACKEND)/.venv

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
	 | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------- setup -----
.PHONY: setup
setup: setup-backend ## Set up everything needed for local development
	@echo "Done. Next: cp .env.example .env && edit it, then 'make db-up migrate run'"

.PHONY: setup-backend
setup-backend: ## Create the backend venv and install dependencies
	cd $(BACKEND) && uv venv .venv && uv pip install --python .venv/bin/python -e ".[dev]"

.PHONY: env
env: ## Create .env from the template if absent
	@test -f .env && echo ".env already exists, leaving it alone." || { \
	  cp .env.example .env; \
	  secret=$$(openssl rand -hex 32); \
	  sed -i '' "s|^AUTH_SECRET=.*|AUTH_SECRET=$$secret|" .env 2>/dev/null \
	    || sed -i "s|^AUTH_SECRET=.*|AUTH_SECRET=$$secret|" .env; \
	  echo "Wrote .env with a generated AUTH_SECRET. Add your GEMINI_API_KEY."; }

# ------------------------------------------------------------- database -----
.PHONY: db-up
db-up: ## Start PostgreSQL in Docker
	cd $(BACKEND) && docker compose up -d db

.PHONY: db-down
db-down: ## Stop PostgreSQL
	cd $(BACKEND) && docker compose down

.PHONY: db-reset
db-reset: ## Destroy and recreate the database volume (DATA LOSS)
	cd $(BACKEND) && docker compose down -v && docker compose up -d db

.PHONY: migrate
migrate: ## Apply migrations
	cd $(BACKEND) && .venv/bin/alembic upgrade head

.PHONY: migration
migration: ## Autogenerate a migration: make migration m="add x"
	cd $(BACKEND) && .venv/bin/alembic revision --autogenerate -m "$(m)"

.PHONY: downgrade
downgrade: ## Roll back one migration
	cd $(BACKEND) && .venv/bin/alembic downgrade -1

# ------------------------------------------------------------------ run -----
.PHONY: run
run: ## Run the backend with reload
	cd $(BACKEND) && .venv/bin/uvicorn app.main:app --reload --port 8099

.PHONY: up
up: ## Run the whole stack in Docker
	cd $(BACKEND) && docker compose up --build

# ---------------------------------------------------------------- tests -----
.PHONY: test
test: test-backend ## Run all tests

.PHONY: test-backend
test-backend: ## Run backend tests
	cd $(BACKEND) && .venv/bin/python -m pytest -q

.PHONY: cov
cov: ## Backend tests with coverage
	cd $(BACKEND) && .venv/bin/python -m pytest --cov=app --cov-report=term-missing:skip-covered

.PHONY: verify-live
verify-live: ## Verify against the REAL Gemini Live API (needs GEMINI_API_KEY)
	cd $(BACKEND) && .venv/bin/python scripts/verify_live_api.py

.PHONY: verify-opening
verify-opening: ## REAL Live session: does it speak first and name the spot?
	cd $(BACKEND) && .venv/bin/python scripts/verify_opening.py

.PHONY: test-android
test-android: ## Run Android unit tests
	cd $(ANDROID) && ./gradlew testDebugUnitTest

.PHONY: web-install
web-install: ## Install web dependencies
	cd $(WEB) && npm install

.PHONY: web
web: ## Run the web app against the local backend
	cd $(WEB) && npm run dev

.PHONY: test-web
test-web: ## Run web unit tests (never contacts Google)
	cd $(WEB) && npm test

.PHONY: web-build
web-build: ## Type-check and build the web app
	cd $(WEB) && npm run build

.PHONY: verify-live-web
verify-live-web: ## REAL Gemini Live session driven by the web client
	cd $(WEB) && FISHMATE_VERIFY_LIVE=1 npm test

# ----------------------------------------------------------------- lint -----
.PHONY: lint
lint: ## Lint backend
	cd $(BACKEND) && .venv/bin/ruff check app tests

.PHONY: fmt
fmt: ## Format backend
	cd $(BACKEND) && .venv/bin/ruff format app tests && .venv/bin/ruff check --fix app tests

.PHONY: fmt-check
fmt-check: ## Verify formatting without changing files
	cd $(BACKEND) && .venv/bin/ruff format --check app tests

.PHONY: typecheck
typecheck: ## Type-check the backend
	cd $(BACKEND) && .venv/bin/mypy app

# -------------------------------------------------------------- android -----
.PHONY: android-build
android-build: ## Assemble the debug APK
	cd $(ANDROID) && ./gradlew assembleDebug

.PHONY: android-install
android-install: ## Install the debug APK on a connected device
	cd $(ANDROID) && ./gradlew installDebug

.PHONY: android-lint
android-lint: ## Run Android lint
	cd $(ANDROID) && ./gradlew lintDebug

# ----------------------------------------------------------------- misc -----
.PHONY: check
check: lint fmt-check typecheck test-backend ## Everything CI runs for the backend

.PHONY: secrets-audit
secrets-audit: ## Fail if a secret looks committed
	@echo "Scanning for committed secrets..."
	@! git ls-files -z 2>/dev/null | xargs -0 grep -lE 'AIza[0-9A-Za-z_-]{35}' 2>/dev/null \
	  || { echo "FAIL: a Google API key pattern is tracked by git"; exit 1; }
	@! git ls-files 2>/dev/null | grep -qx '.env' \
	  || { echo "FAIL: .env is tracked by git"; exit 1; }
	@echo "OK: no API-key patterns and no .env in the index."

.PHONY: clean
clean: ## Remove caches and build output
	find . -name __pycache__ -type d -prune -exec rm -rf {} + 2>/dev/null || true
	rm -rf $(BACKEND)/.pytest_cache $(BACKEND)/.ruff_cache $(BACKEND)/.mypy_cache \
	       $(BACKEND)/htmlcov $(BACKEND)/.coverage $(ANDROID)/build $(ANDROID)/app/build
