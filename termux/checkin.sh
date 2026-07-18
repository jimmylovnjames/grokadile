#!/data/data/com.termux/files/usr/bin/bash
# One-shot Grokadile proactive check-in, for termux-job-scheduler / cron.
# Respects quiet hours and the GROKADILE_CHECKIN_MINUTES rate limit.
cd "$(dirname "$0")"
exec python grokadile.py --checkin
