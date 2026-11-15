FROM python:3.12-slim
WORKDIR /app
COPY bildirici.py .
ENV STATE_FILE=/data/durum.json PYTHONUNBUFFERED=1
VOLUME /data
EXPOSE 8080
CMD ["python", "bildirici.py"]
