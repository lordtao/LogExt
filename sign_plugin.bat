@echo off
setlocal

set /p PWD="Введите пароль для подписания (если есть, иначе нажмите Enter): "

echo Запуск задачи подписания плагина...
if "%PWD%"=="" (
    call gradlew signPlugin
) else (
    call gradlew signPlugin -PpluginSigningPassword=%PWD%
)

if %ERRORLEVEL% equ 0 (
    echo.
    echo Плагин успешно подписан!
    echo Дистрибутив находится в build/distributions/
) else (
    echo.
    echo Ошибка при подписании плагина.
)

pause
