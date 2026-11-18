@echo off
>nul chcp 65001
rem Биржа: Spring Boot сервис с состоянием в памяти.
rem Ключи, которые пригодятся на практике:
rem   --exchange.strategy=none|sync|trylock|locks|rw|optimistic|queue|semaphore|deadlock
rem   --exchange.hazard-micros=150      (0 = гонку придётся ловить долгим прогоном)
rem   --server.port=8081                (если 8080 занят соседней тройкой)
setlocal
cd /d %~dp0..
set JAR=exchange-server\target\exchange-server-1.0.0-exec.jar
if not exist "%JAR%" (
  echo Соберите проект: mvn -DskipTests package
  exit /b 1
)
java -jar "%JAR%" %*
