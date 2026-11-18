@echo off
>nul chcp 65001
rem Клиент-покупатель. Смотри --help, он печатает все ключи.
setlocal
cd /d %~dp0..
set JAR=buyer-client\target\buyer-client-1.0.0.jar
if not exist "%JAR%" (
  echo Соберите проект: mvn -DskipTests package
  exit /b 1
)
java -jar "%JAR%" %*
