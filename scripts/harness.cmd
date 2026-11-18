@echo off
>nul chcp 65001
rem Прогон всех стратегий на одинаковой нагрузке + сводная таблица.
rem Полезные ключи: --only=none,sync  --scenario=hot  --read-share=0.9  --hazard=600
rem                --ifmatch=true  --repeat=3  --csv=out/run.csv  --server=http://localhost:8080
setlocal
cd /d %~dp0..
set JAR=lab-harness\target\lab-harness-1.0.0.jar
if not exist "%JAR%" (
  echo Соберите проект: mvn -DskipTests package
  exit /b 1
)
if not exist out mkdir out
java -Dfile.encoding=UTF-8 -jar "%JAR%" %*
