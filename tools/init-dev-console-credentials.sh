#!/usr/bin/env bash
set -euo pipefail

# One-time initialization for a newly cloned local database. The application remains running afterwards.
MODELGATE_DEVELOPMENT_DEFAULT_CREDENTIALS_ENABLED=true \
MODELGATE_INITIALIZE_DEVELOPMENT_CREDENTIALS=true \
mvn -pl modelgate-bootstrap spring-boot:run
