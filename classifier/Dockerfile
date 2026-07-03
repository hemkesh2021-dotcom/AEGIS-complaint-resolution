FROM python:3.11-slim

WORKDIR /app

# System deps kept minimal; torch wheels are self-contained.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

# Where the fine-tuned model is mounted/copied. For Cloud Run, either bake the
# model into the image (COPY it here) or pull it from GCS at startup.
ENV MODEL_DIR=/models/complaint_classifier

EXPOSE 8000
CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-8000}"]
