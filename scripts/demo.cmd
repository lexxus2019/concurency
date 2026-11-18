@echo off
>nul chcp 65001
rem Демо на 60 секунд для показа на проекторе: три окна, одна биржа, два клиента.
setlocal
cd /d %~dp0..
if not exist exchange-server\target\exchange-server-1.0.0-exec.jar (
  echo Соберите проект: mvn -DskipTests package
  exit /b 1
)
start "биржа" cmd /k scripts\run-server.cmd --exchange.strategy=none
timeout /t 4 /nobreak >nul
start "покупатель" cmd /k scripts\run-buyer.cmd --ops=200 --threads=8
start "продавец" cmd /k scripts\run-seller.cmd --ops=80 --threads=2
echo Открой в браузере: http://localhost:8080/api/lab/state
echo Переключение стратегии без рестарта: curl -X POST "http://localhost:8080/api/lab/strategy?name=sync"
