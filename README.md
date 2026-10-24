# springmvc-probe

Spring MVC 6（Boot なし、Java Config）のヘルスチェックサンプル。Spring の JAR は WAR に同梱。スレッドは EAP の `ManagedExecutorService`（`java:jboss/ee/concurrency/executor/default`）を使う。DB チェックは Spring の `/health/ready` と、CDI の `@Readiness`（`DbReadinessCheck`）で同じ処理を使う。

- `GET /health/ready`：プールからコネクションを借りて `isValid(2)` を実行。`CompletableFuture` で別スレッド実行し、3 秒で打ち切り。成功で 200 `{"status":"UP"}`、失敗・タイムアウトで 503 `{"status":"DOWN","reason":"..."}`
- `GET /health/test?seconds=30&count=3`：テスト用。コネクションを `count` 本掴み、1本で `DBMS_SESSION.SLEEP` する。`count` は `max-pool-size` 以下。プールを埋め切ると、並行の `/ready` は 3 秒で打ち切るが、裏の `getConnection` は空き待ちのまま残ることがある。環境変数 `PROBE_DB_CHECK_TEST_ENABLE=true` のときだけ有効（未設定は 404）
- `GET /health/live`：DB を見ずに 200 `{"status":"UP"}`
- CDI `@Readiness`：同じ DB チェック。`PROBE_DB_CHECK_ENABLED=true` のときだけ登録。EAP の MicroProfile Health `GET http://localhost:9990/health/ready` の `checks` に載る（既定名は `PROBE_DB_CHECK_NAME`）

## ビルド

```bash
mvn package   # target/springmvc-probe.war ができる（Java 21）
```

## EAP 8.1 へのデプロイ

Oracle JDBC モジュール（`com.oracle.ojdbc`）と `java:jboss/datasources/OracleDS` が必要。手元の EAP では次を登録済み。

- モジュール: `$EAP_HOME/modules/com/oracle/ojdbc/main/`（ojdbc11 23.9）
- URL: `jdbc:oracle:thin:@//localhost:1521/XEPDB1`
- ユーザー: `user01`

```bash
cp target/springmvc-probe.war $EAP_HOME/standalone/deployments/
# または jboss-cli: deploy target/springmvc-probe.war
```

## 動作確認

```bash
curl -i http://localhost:8080/springmvc-probe/health/live
curl -i http://localhost:8080/springmvc-probe/health/ready
curl -i http://localhost:9990/health/ready   # CDI @Readiness（db）

# プール満杯（PROBE_DB_CHECK_TEST_ENABLE=true のときだけ。count は max-pool-size 以下。別ターミナルで test を先に起動）
curl -i "http://localhost:8080/springmvc-probe/health/test?seconds=30&count=3"
curl -i http://localhost:8080/springmvc-probe/health/ready
curl -i http://localhost:9990/health/ready
```

## OpenShift の Probe 例

```yaml
readinessProbe:
  httpGet: { path: /springmvc-probe/health/ready, port: 8080 }
  periodSeconds: 10
  timeoutSeconds: 5      # アプリ側の打ち切り(3秒)より長く
  failureThreshold: 3
livenessProbe:
  httpGet: { path: /springmvc-probe/health/live, port: 8080 }   # DB は見ない
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
```

## 注意

- CDI `@Readiness`（`DbReadinessCheck`）は `PROBE_DB_CHECK_ENABLED=true`（`1` / `yes` も可）のときだけ有効。未設定は登録しない
- 次は環境変数で上書き可（未設定は定数の既定値）
  - `PROBE_DB_CHECK_DS_JNDI`（既定 `java:jboss/datasources/OracleDS`）
  - `PROBE_DB_CHECK_VALID_TIMEOUT_SEC`（既定 `2`）
  - `PROBE_DB_CHECK_TOTAL_TIMEOUT_SEC`（既定 `3`）
  - `PROBE_DB_CHECK_NAME`（CDI `@Readiness` の checks 名。未設定は `DbReadinessCheck.NAME`）
- スレッドは `DbConnectionCheck.EXECUTOR_JNDI`（`java:jboss/ee/concurrency/executor/default`。`java:comp/DefaultManagedExecutorService` と同じ default プール）。管理ポートの Health は `java:comp` を持たないためグローバル JNDI を使う。コンテナ管理のためアプリから shutdown しない
- MicroProfile Health は管理ポート（既定 9990）。`security-enabled=false` のとき認証なし
- `/health/test` は環境変数 `PROBE_DB_CHECK_TEST_ENABLE=true`（`1` / `yes` も可）のときだけ有効。未設定・それ以外は 404
- コネクション取得の待ち時間はデータソースの `blocking-timeout-wait-millis` で決まる。打ち切り後も裏の処理はその時間まで残ることがある
