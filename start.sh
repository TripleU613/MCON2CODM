#!/system/bin/sh
# Start the MCON → Xbox bridge (single native binary).
PIDFILE=/data/local/tmp/mcon_bridge.pid
BIN=/data/local/tmp/mcon_bridge

if [ -f "$PIDFILE" ] && kill -0 "$(cat $PIDFILE)" 2>/dev/null; then
  echo "Already running (pid=$(cat $PIDFILE))"
  exit 0
fi

if [ ! -x "$BIN" ]; then
  echo "Missing $BIN — push it first"
  exit 1
fi

nohup "$BIN" >/dev/null 2>/data/local/tmp/mcon_bridge.log &
echo $! > $PIDFILE
echo "Bridge started (pid=$(cat $PIDFILE))"
