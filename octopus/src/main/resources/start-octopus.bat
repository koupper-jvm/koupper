@echo off
cd /d "%userprofile%\.koupper\libs"
java -Dfile.encoding=UTF-8 -cp "*" com.koupper.octopus.OctopusKt > "%userprofile%\.koupper\logs\octopus.log" 2>&1
