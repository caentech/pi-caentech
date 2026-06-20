.PHONY: run love web web-serve build clean help
.DEFAULT_GOAL := help

run: ## Démarre l'application (API + frontend) sur http://localhost:10028
	./gradlew run

love: ## Lance l'application d'affichage LÖVE localement
	love pi-app/love

web: ## Construit la preview web de l'app LÖVE (cache embarqué) dans pi-app/web/dist
	./pi-app/web/build-web.sh

web-serve: ## Sert la preview web sur http://localhost:8080 (après make web)
	cd pi-app/web/dist && python3 -m http.server 8080

build: ## Compile et assemble le projet
	./gradlew build

clean: ## Nettoie les artefacts de build
	./gradlew clean

help: ## Affiche cette aide
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-8s\033[0m %s\n", $$1, $$2}'
