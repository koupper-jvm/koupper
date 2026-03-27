@echo off
setlocal

REM Cambiar la página de códigos a UTF-8
chcp 65001 >nul

REM Ejecutar el archivo JAR con la codificación UTF-8
java -Dfile.encoding=UTF-8 -jar "%userprofile%\.koupper\libs\koupper-cli-3.5.0.jar" %*

REM Verificar si existe el archivo updateme.kts
if exist "%userprofile%\.koupper\helpers\updateme.kts" (
    echo Applying updates, wait a while...
    kotlinc -script "%userprofile%\.koupper\helpers\updateme.kts"
    del "%userprofile%\.koupper\helpers\updateme.kts"
)

if exist "%userprofile%\.koupper\helpers\octopus-parameters.txt" (
    REM Leer el archivo línea por línea
    for /f "tokens=1,2" %%a in (%userprofile%\.koupper\helpers\octopus-parameters.txt) do (
        "%userprofile%\.koupper\helpers\octopusInvoker.bat" %%a %%b
    )
)

endlocal
