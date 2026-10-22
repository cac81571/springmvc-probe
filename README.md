# springmvc-probe

Spring MVC 6（Boot なし、Java Config）のヘルスチェックサンプル。Spring の JAR は WAR に同梱。スレッドは EAP の `ManagedExecutorService`（java:comp/DefaultManagedExecutorService）を JNDI で取得。

- `GET /health/ready`：プールからコネクションを借りて `isValid(2)` を実行。`CompletableFuture` で別スレッド実行し、3 秒で打ち切り。成功で 200 `{"status":"UP"}`、失敗・タイムアウトで 503 `{"status":"DOWN","reason":"..."}`
- `GET /health/hold?seconds=30&count=3`：コネクションを `count` 本掴み、1本で `DBMS_SESSION.SLEEP` する。`count` は `max-pool-size` 以下。プールを埋め切ると、並行の `/ready` は 3 秒で打ち切るが、裏の `getConnection` は空き待ちのまま残ることがある。環境変数 `APP_ENV=prod` のときは 404
- `GET /health/live`：DB を見ずに 200 `{"status":"UP"}`

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

# プール満杯（count は max-pool-size 以下。APP_ENV=prod では 404。別ターミナルで hold を先に起動）
curl -i "http://localhost:8080/springmvc-probe/health/hold?seconds=30&count=3"
curl -i http://localhost:8080/springmvc-probe/health/ready
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

- JNDI 名は `AppConfig.DS_JNDI` 定数（既定 `java:jboss/datasources/OracleDS`）
- スレッドは `AppConfig.EXECUTOR_JNDI`（`java:comp/DefaultManagedExecutorService`）。コンテナ管理のためアプリから shutdown しない
- コネクション取得の待ち時間はデータソースの `blocking-timeout-wait-millis` で決まる。打ち切り後も裏の処理はその時間まで残ることがある
