@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
set "HF_ENDPOINT=https://hf-mirror.com"

python -c "import numpy, onnxruntime, tokenizers, huggingface_hub" >nul 2>&1
if errorlevel 1 (
    echo Installing local inference dependencies...
    python -m pip install numpy onnxruntime tokenizers huggingface_hub
    if errorlevel 1 exit /b 1
)

python scripts\download_model.py --target models\multilingual-minilm
if errorlevel 1 exit /b 1

echo.
echo Model installation completed. Rebuild the index in SimpleRAG.
pause
endlocal
