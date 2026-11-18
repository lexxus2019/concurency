@echo off
>nul chcp 65001
rem Клиент-продавец: выставляет лоты, уценивает (lost update-детектор внутри), отзывает витрины.
setlocal
cd /d %~dp0..
set JAR=seller-client\target\seller-client-1.0.0.jar
if not exist "%JAR%" (
  echo Соберите проект: mvn -DskipTests package
  exit /b 1
)
java -jar "%JAR%" %*
