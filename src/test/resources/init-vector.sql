-- 운영 DB 초기 구성(bootstrap/db/00-bootstrap.sql)과 같이 pgvector를 public에 설치한다
CREATE EXTENSION IF NOT EXISTS vector;
