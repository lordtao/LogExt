@echo off
setlocal

echo Генерация приватного ключа...
openssl genrsa -out private_key.pem 4096

echo Генерация цепочки сертификатов (самоподписанный сертификат)...
openssl req -new -x509 -key private_key.pem -out certificate_chain.crt -days 365 -subj "/CN=TAO LogExt Plugin"

echo.
echo Ключи успешно сгенерированы:
echo - private_key.pem (Приватный ключ)
echo - certificate_chain.crt (Цепочка сертификатов)
echo.
echo ВНИМАНИЕ: Не передавайте private_key.pem третьим лицам!
pause
