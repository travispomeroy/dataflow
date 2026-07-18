#!/bin/sh
# SSH_ASKPASS helper for the gate's non-interactive sftp login (e2e/m0-gates.sh).
# Keeps the clean-machine prerequisites at Docker/Node/git/JDK — no sshpass.
printf '%s\n' "$SFTP_PASSWORD"
