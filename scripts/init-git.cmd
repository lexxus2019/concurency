@echo off
>nul chcp 65001
rem Инициализация репозитория под командную работу на практике.
rem Делает: git init, первый коммит, по ветке на стратегию (каждая тройка работает в своей).
setlocal
cd /d %~dp0..

if exist .git (
  echo Репозиторий уже инициализирован.
) else (
  git init -b main
  git add .gitignore pom.xml README.md exchange-core exchange-server buyer-client seller-client lab-harness docs scripts
  git commit -m "exchange-lab: биржа, два клиента, девять стратегий синхронизации, харнес"
)

for %%s in (sync trylock locks rw optimistic queue semaphore) do (
  git rev-parse --verify --quiet strategy/%%s >nul || git branch strategy/%%s main
)

echo.
echo Ветки:
git branch
echo.
echo Каждой тройке: git switch strategy/<имя>, пишем свой ExchangeEngine,
echo потом git switch main ^&^& git merge strategy/<имя>  (и смотрим, где конфликт).
echo Тренировка конфликта специально: пусть две ветки допишут своё имя в
echo EngineInvariantTest.fullyCleanStrategies() - это единственный общий список.
