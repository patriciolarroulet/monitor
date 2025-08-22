@echo off
REM === Configuración de variables ===
set "EXCEL=C:\Users\l_tor\Desktop\monitor-lecaps\src\main\resources\data\API LECAPS_con_comisiones.xlsx"
set "SHEET=Detalle"

REM === Ir a la carpeta del proyecto ===
cd /d "%~dp0"

REM === Compilar el proyecto si es necesario ===
if not exist "target\monitor-lecaps-0.0.1-SNAPSHOT.jar" (
    echo Compilando proyecto...
    mvn clean package
)

REM === Ejecutar la aplicación ===
echo Iniciando servidor Spring Boot...
java -jar target\monitor-lecaps-0.0.1-SNAPSHOT.jar ^
 --server.port=8080 ^
 --monitor.excel.path="file:///%EXCEL%" ^
 --monitor.excel.sheet=%SHEET%

pause
