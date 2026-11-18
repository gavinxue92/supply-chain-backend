param(
    [switch]$UseDocker,
    [string]$MySqlExecutable,
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'root'
)

$ErrorActionPreference = 'Stop'
$schemaPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'src\main\resources\db\migration\V1__initial_schema.sql'
if (-not (Test-Path -LiteralPath $schemaPath -PathType Leaf)) {
    throw "Schema migration not found: $schemaPath"
}

$sql = [System.IO.File]::ReadAllText($schemaPath)
$expectedTables = @(
    'admin_users',
    'admin_sessions',
    'media_resources',
    'categories',
    'global_contact_configs',
    'products',
    'product_specs',
    'product_keywords',
    'product_gallery',
    'audit_logs'
)

foreach ($table in $expectedTables) {
    $pattern = "(?is)CREATE\s+TABLE\s+$table\s*\((.*?)\)\s*ENGINE\s*=\s*InnoDB\s+DEFAULT\s+CHARACTER\s+SET\s*=\s*utf8mb4\s+COLLATE\s*=\s*utf8mb4_unicode_ci\s*;"
    $match = [regex]::Match($sql, $pattern)
    if (-not $match.Success) {
        throw "Missing table or required MySQL table options: $table"
    }
    foreach ($column in @('created_at', 'updated_at', 'is_deleted')) {
        if ($match.Groups[1].Value -notmatch "(?im)^\s*$column\s+") {
            throw "Table $table is missing required column $column"
        }
    }
}

if ($sql -notmatch '(?i)reference_price\s+DECIMAL\s*\(\s*10\s*,\s*2\s*\)') {
    throw 'Reference prices must use DECIMAL(10,2)'
}
if ($sql -notmatch '(?i)FOREIGN KEY') {
    throw 'Expected foreign key constraints were not found'
}

Write-Output "Static schema contract checks passed for $($expectedTables.Count) tables."

if ($UseDocker -and $MySqlExecutable) {
    throw 'Choose either -UseDocker or -MySqlExecutable, not both.'
}

if ($MySqlExecutable) {
    if (-not (Test-Path -LiteralPath $MySqlExecutable -PathType Leaf)) {
        throw "MySQL client executable not found: $MySqlExecutable"
    }

    $database = "schema_validation_$([guid]::NewGuid().ToString('N').Substring(0, 12))"
    $mysqlPassword = $env:MYSQL_VALIDATION_PASSWORD
    $previousMysqlPassword = $env:MYSQL_PWD
    if ($mysqlPassword) {
        $env:MYSQL_PWD = $mysqlPassword
    }
    $connection = @(
        '--protocol=TCP',
        "--host=$MySqlHost",
        "--port=$MySqlPort",
        "--user=$MySqlUser",
        '--batch',
        '--default-character-set=utf8mb4'
    )
    $databaseCreated = $false
    try {
        & $MySqlExecutable @connection --execute="CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
        if ($LASTEXITCODE -ne 0) {
            throw 'Could not create a temporary schema for MySQL validation.'
        }
        $databaseCreated = $true

        Get-Content -LiteralPath $schemaPath -Raw |
            & $MySqlExecutable @connection $database
        if ($LASTEXITCODE -ne 0) {
            throw 'MySQL rejected the schema migration.'
        }

        $tableCount = & $MySqlExecutable @connection --skip-column-names $database `
            --execute="SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$database' AND TABLE_TYPE='BASE TABLE'"
        if ($LASTEXITCODE -ne 0 -or [int]$tableCount.Trim() -ne $expectedTables.Count) {
            throw "Expected $($expectedTables.Count) tables after migration; found $tableCount."
        }
        $auditCount = & $MySqlExecutable @connection --skip-column-names $database `
            --execute="SELECT COUNT(*) FROM (SELECT TABLE_NAME, COUNT(DISTINCT COLUMN_NAME) AS n FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$database' AND COLUMN_NAME IN ('created_at','updated_at','is_deleted') GROUP BY TABLE_NAME HAVING n=3) audit_tables"
        if ($LASTEXITCODE -ne 0 -or [int]$auditCount.Trim() -ne $expectedTables.Count) {
            throw "Expected required audit columns on all core tables; found $auditCount."
        }
        $tableOptions = & $MySqlExecutable @connection --skip-column-names $database `
            --execute="SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$database' AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB' AND TABLE_COLLATION='utf8mb4_unicode_ci'"
        if ($LASTEXITCODE -ne 0 -or [int]$tableOptions.Trim() -ne $expectedTables.Count) {
            throw "Expected InnoDB/utf8mb4_unicode_ci on every core table; found $tableOptions."
        }
        $priceType = & $MySqlExecutable @connection --skip-column-names $database `
            --execute="SELECT CONCAT(DATA_TYPE, '(', NUMERIC_PRECISION, ',', NUMERIC_SCALE, ')') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$database' AND TABLE_NAME='product_specs' AND COLUMN_NAME='reference_price'"
        if ($LASTEXITCODE -ne 0 -or $priceType.Trim() -ne 'decimal(10,2)') {
            throw "Expected reference_price DECIMAL(10,2); found $priceType."
        }
        Write-Output "MySQL accepted the migration: $tableCount tables, required audit fields and table options verified."
    }
    finally {
        if ($databaseCreated) {
            & $MySqlExecutable @connection --execute="DROP DATABASE IF EXISTS $database" 2>$null | Out-Null
        }
        if ($null -eq $previousMysqlPassword) {
            Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
        } else {
            $env:MYSQL_PWD = $previousMysqlPassword
        }
    }
}

if ($UseDocker) {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        throw 'Docker CLI is not installed; cannot run MySQL 8.0 syntax/integration validation.'
    }
    $dockerInfo = & docker info --format '{{.ServerVersion}}' 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Engine is unavailable: $dockerInfo"
    }

    $database = 'schema_validation'
    $password = 'schema-test-password'
    $containerName = "schema-validation-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
    try {
        & docker run --detach --rm --name $containerName `
            --env "MYSQL_ROOT_PASSWORD=$password" `
            --env "MYSQL_DATABASE=$database" `
            mysql:8.0 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw 'Failed to start MySQL 8.0 container.'
        }

        $ready = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            & docker exec $containerName mysqladmin ping --host=127.0.0.1 --user=root "--password=$password" --silent 2>$null
            if ($LASTEXITCODE -eq 0) {
                $ready = $true
                break
            }
            Start-Sleep -Seconds 2
        }
        if (-not $ready) {
            throw 'MySQL 8.0 did not become ready within 120 seconds.'
        }

        Get-Content -LiteralPath $schemaPath -Raw |
            & docker exec --interactive $containerName mysql --user=root "--password=$password" $database
        if ($LASTEXITCODE -ne 0) {
            throw 'MySQL 8.0 rejected the schema migration.'
        }

        $tableCount = & docker exec $containerName mysql --batch --skip-column-names `
            --user=root "--password=$password" $database `
            --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$database' AND table_type='BASE TABLE'"
        if ($LASTEXITCODE -ne 0 -or [int]$tableCount.Trim() -ne $expectedTables.Count) {
            throw "Expected $($expectedTables.Count) tables after migration; found $tableCount."
        }
        Write-Output "MySQL 8.0 accepted the migration and created $($expectedTables.Count) core tables."
    }
    finally {
        & docker rm --force $containerName 2>$null | Out-Null
    }
}
