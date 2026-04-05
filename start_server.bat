@echo off
cd /d "C:\Program Files\Git\agentic-paradise\smallville"
start java -jar "target\smallville-1.3.0.jar" --port 8080 > server.log 2>&1
