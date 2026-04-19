#!/system/bin/sh
PIDFILE=/data/local/tmp/mcon_bridge.pid
if [ -f "$PIDFILE" ]; then
  kill "$(cat $PIDFILE)" 2>/dev/null
  sleep 1
  kill -9 "$(cat $PIDFILE)" 2>/dev/null
  rm -f "$PIDFILE"
fi
pkill -9 -f /data/local/tmp/mcon_bridge 2>/dev/null
echo "Stopped."
