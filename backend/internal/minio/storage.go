package minio

import (
	"bytes"
	"context"
	"io"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

type Storage struct {
	client *minio.Client
	bucket string
}

func New(endpoint, accessKey, secretKey, bucket string) (*Storage, error) {
	client, err := minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
		Secure: false,
	})
	if err != nil {
		return nil, err
	}

	exists, err := client.BucketExists(context.Background(), bucket)
	if err != nil {
		return nil, err
	}
	if !exists {
		if err := client.MakeBucket(context.Background(), bucket, minio.MakeBucketOptions{}); err != nil {
			return nil, err
		}
	}

	return &Storage{client: client, bucket: bucket}, nil
}

func (s *Storage) PutFile(objectName string, data []byte) (string, error) {
	_, err := s.client.PutObject(context.Background(), s.bucket, objectName,
		bytes.NewReader(data), int64(len(data)),
		minio.PutObjectOptions{},
	)
	if err != nil {
		return "", err
	}
	return objectName, nil
}

func (s *Storage) GetFile(objectName string) (string, error) {
	obj, err := s.client.GetObject(context.Background(), s.bucket, objectName, minio.GetObjectOptions{})
	if err != nil {
		return "", err
	}
	defer obj.Close()
	data, err := io.ReadAll(obj)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func (s *Storage) ListFiles(prefix string) ([]string, error) {
	var result []string
	for obj := range s.client.ListObjects(context.Background(), s.bucket, minio.ListObjectsOptions{
		Prefix:    prefix,
		Recursive: true,
	}) {
		if obj.Err != nil {
			return result, obj.Err
		}
		result = append(result, obj.Key)
	}
	return result, nil
}

func (s *Storage) CheckFileExists(objectName string) bool {
	_, err := s.client.StatObject(context.Background(), s.bucket, objectName, minio.StatObjectOptions{})
	return err == nil
}

func (s *Storage) PutFileString(objectName string, content string) (string, error) {
	return s.PutFile(objectName, []byte(content))
}

func (s *Storage) DeleteFile(objectName string) error {
	return s.client.RemoveObject(context.Background(), s.bucket, objectName, minio.RemoveObjectOptions{})
}
