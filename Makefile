.PHONY: run love build clean help
.DEFAULT_GOAL := help

run: ## Démarre l'application (API + frontend) sur http://localhost:10028
	./gradlew run

love: ## Lance l'application d'affichage LÖVE localement
	love pi-app/love

build: ## Compile et assemble le projet
	./gradlew build

clean: ## Nettoie les artefacts de build
	./gradlew clean

help: ## Affiche cette aide
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-8s\033[0m %s\n", $$1, $$2}'
