@echo off
echo ===================================================
echo   LKS Dialer Web - Cloudflare Pages Deployment
echo ===================================================
echo.
cd /d "%~dp0"
echo 1. Ensuring production build is up to date...
call npm run build
if %errorlevel% neq 0 (
    echo [ERROR] Build failed!
    pause
    exit /b %errorlevel%
)
echo.
echo 2. Checking / Refreshing Cloudflare Login...
call npx wrangler login
if %errorlevel% neq 0 (
    echo [ERROR] Wrangler login failed!
    pause
    exit /b %errorlevel%
)
echo.
echo 3. Deploying to Cloudflare Pages (project: lksdialerweb)...
call npx wrangler pages deploy dist --project-name lksdialerweb
if %errorlevel% neq 0 (
    echo [ERROR] Deployment failed!
    pause
    exit /b %errorlevel%
)
echo.
echo ===================================================
echo   SUCCESS! Deployed to https://lksdialerweb.pages.dev
echo ===================================================
pause
