# Lazy Dog README (Docker Compose: Elasticsearch + Kibana + Ollama)

This folder contains a `docker-compose.yml` that runs:

* Elasticsearch ([http://localhost:9200](http://localhost:9200))
* Kibana ([http://localhost:5601](http://localhost:5601))
* Ollama ([http://localhost:11434](http://localhost:11434))

Everything runs locally using Docker Desktop.

---

## 0) Prerequisites

* Docker Desktop installed and running (whale icon shows Docker is running)
* Docker Compose works:

```bash
docker compose version
```

---

## 1) Start everything (recommended)

From this folder:

```bash
docker compose up -d
```

---

## 2) Check status

```bash
docker compose ps
```

You want to see all services as `Up`.
Note: Kibana can take a minute the first time.

---

## 3) Quick health checks

### Elasticsearch

```bash
curl http://localhost:9200
```

Expected: JSON response containing cluster name and version.

### Kibana

Open in browser:

* [http://localhost:5601](http://localhost:5601)

Expected: Kibana UI loads.

### Ollama

```bash
curl http://localhost:11434/api/tags
```

Expected: JSON response. An empty list is OK if you have no models yet.

---

## 4) View logs (when something is wrong)

All services:

```bash
docker compose logs -f
```

Only one service:

```bash
docker compose logs -f elasticsearch
docker compose logs -f kibana
docker compose logs -f ollama
```

Stop following logs: `Ctrl + C`

---

## 5) Stop everything (keep data)

```bash
docker compose down
```

This stops containers but keeps volumes (your Elasticsearch data and Ollama models).

---

## 6) Reset everything (delete all data)

Warning: This deletes:

* Elasticsearch indexes/data
* Ollama downloaded models

```bash
docker compose down -v
```

---

## 7) Restart services

Restart everything:

```bash
docker compose restart
```

Restart one service:

```bash
docker compose restart elasticsearch
docker compose restart kibana
docker compose restart ollama
```

---

## 8) Common fixes

### Kibana is not reachable

Kibana often starts slower than Elasticsearch. Check:

```bash
docker compose logs -f kibana
```

### Elasticsearch memory or startup issues

Check Elasticsearch logs:

```bash
docker compose logs -f elasticsearch
```

### Linux-only: vm.max_map_count error

Not needed on macOS Docker Desktop. If you run this on Linux and Elasticsearch complains about `vm.max_map_count`:

```bash
sudo sysctl -w vm.max_map_count=262144
```

---

## 9) Ollama quick usage

Pull a model (example):

```bash
docker exec -it ollama ollama pull llama3
```

Run a model:

```bash
docker exec -it ollama ollama run llama3
```

List models:

```bash
docker exec -it ollama ollama list
```

---

## 10) Useful URLs

* Elasticsearch: [http://localhost:9200](http://localhost:9200)
* Kibana: [http://localhost:5601](http://localhost:5601)
* Ollama API: [http://localhost:11434](http://localhost:11434)

---

## Notes

* This setup is intended for local/dev use.
* Elasticsearch security is disabled for simplicity.
* Data is persisted using Docker volumes.
