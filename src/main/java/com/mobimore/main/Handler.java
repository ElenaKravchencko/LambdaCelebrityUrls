package com.mobimore.main;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2ProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2ProxyResponseEvent;
import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.model.Celebrity;
import com.amazonaws.services.rekognition.model.Image;
import com.amazonaws.services.rekognition.model.RecognizeCelebritiesRequest;
import com.amazonaws.services.rekognition.model.RecognizeCelebritiesResult;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.util.StringUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mobimore.domain.File;
import com.mobimore.domain.Request;
import com.mobimore.model.Actor;
import com.mobimore.model.ActorInfo;
import info.movito.themoviedbapi.TmdbApi;
import info.movito.themoviedbapi.TmdbPeople;
import info.movito.themoviedbapi.model.people.Person;
import info.movito.themoviedbapi.model.people.PersonExternalIds;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.persistence.NoResultException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

public class Handler implements RequestHandler<APIGatewayV2ProxyRequestEvent, APIGatewayV2ProxyResponseEvent> {
  LambdaLogger logger;
  final String celebritiesS3Bucket = "celebrities-s3";
  final String apiKey = "62c3b228d3fd30737ea62a5fad0adb7f";
  final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  final AmazonRekognition rek = AmazonRekognitionClientBuilder.standard().withRegion(Regions.US_EAST_1).build();

  @Override
  public APIGatewayV2ProxyResponseEvent handleRequest(APIGatewayV2ProxyRequestEvent event, Context context) {
    logger = context.getLogger();

    String eventBody = event.getBody();
    if (eventBody != null && !eventBody.isEmpty()) {
      Actor actor = gson.fromJson(eventBody, Actor.class);
      byte[] imageBytes = Base64.getDecoder().decode(actor.getImageBase64().getData());

      String format = determineImageFormat(imageBytes);

      if (format == null) {
        return createErrorResponse("image format can't be determined");
      }

      Image image = new Image();
      image.setBytes(ByteBuffer.wrap(imageBytes));

      String imageHash = md5(imageBytes);

      AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();

      String imageName = imageHash + "." + format;
      if (!s3Client.doesObjectExist(celebritiesS3Bucket, imageName)) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(imageBytes.length);
        metadata.setContentType("image/" + format);
        PutObjectRequest request = new PutObjectRequest(celebritiesS3Bucket, imageName, new ByteArrayInputStream(imageBytes), metadata);
        s3Client.putObject(request);
      }

      RecognizeCelebritiesRequest celebritiesRequest = new RecognizeCelebritiesRequest();
      celebritiesRequest.setImage(image);

      RecognizeCelebritiesResult celebritiesResult = rek.recognizeCelebrities(celebritiesRequest);
      List<Celebrity> celebrityList = celebritiesResult.getCelebrityFaces();

      if (celebrityList != null && celebrityList.size() > 0) {
        List<ActorInfo> actorInfos = celebrityList.stream().map(this::parseCelebrityToActorInfo).collect(Collectors.toList());

        List<Request> requests = actorInfos.stream().map(e -> createRequest(e, celebritiesS3Bucket, imageName)).collect(Collectors.toList());
        persistRequests(requests);

        APIGatewayV2ProxyResponseEvent response = new APIGatewayV2ProxyResponseEvent();
        response.setIsBase64Encoded(false);
        response.setStatusCode(200);
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/html");
        response.setHeaders(headers);

        String body = gson.toJson(actorInfos);
        response.setBody(body);
        return response;
      }
    }
    return createErrorResponse("data not found for requested image");
  }

  private APIGatewayV2ProxyResponseEvent createErrorResponse(String message) {
    APIGatewayV2ProxyResponseEvent response = new APIGatewayV2ProxyResponseEvent();
    response.setIsBase64Encoded(false);
    response.setStatusCode(404);
    HashMap<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "text/html");
    response.setHeaders(headers);
    response.setBody("{\n\"message\":" + "\"" + message + "\"" + "}");
    return response;
  }

  private PersonExternalIds getTMDBInfo(String celebrityName) {
    TmdbApi api = new TmdbApi(apiKey);
    TmdbPeople.PersonResultsPage personSearchResults = api.getSearch().searchPerson(celebrityName, true, 1);
    List<Person> personList = personSearchResults.getResults();
    return api.getPeople().getPersonExternalIds(personList.get(0).getId());
  }

  private static void addIfNotEmpty(List<String> list, String externalId, String mainUrls) {
    if (!StringUtils.isNullOrEmpty(externalId)) {
      list.add(mainUrls + "" + externalId);
    }
  }

  private ActorInfo parseCelebrityToActorInfo(Celebrity celebrity) {
    List<String> urls = new ArrayList<>(celebrity.getUrls());

    try {
      PersonExternalIds externalIds = getTMDBInfo(celebrity.getName());
      if (externalIds != null) {
        if (urls.stream().noneMatch(e -> e.contains("imdb"))) {
          addIfNotEmpty(urls, externalIds.getImdbId(), "https://www.imdb.com/name/");
        }
        addIfNotEmpty(urls, externalIds.getTwitterId(), "https://twitter.com/");
        addIfNotEmpty(urls, externalIds.getFacebookId(), "https://facebook.com/");
        addIfNotEmpty(urls, externalIds.getInstagramId(), "https://instagram.com/");
      }
      //Catch all errors
    } catch (Exception e) {
      logger.log("TMDB info not found for: " + celebrity.getName());
    }

    ActorInfo actorInfo = new ActorInfo();
    actorInfo.setId(celebrity.getId());
    actorInfo.setName(celebrity.getName());
    actorInfo.setUrls(urls);
    return actorInfo;
  }

  private static String md5(byte[] sourceData) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
      byte[] array = md.digest(sourceData);
      StringBuilder sb = new StringBuilder();
      for (byte b : array) {
        sb.append(Integer.toHexString((b & 0xFF) | 0x100).substring(1, 3));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException ignored) {
    }
    return null;
  }

  private Request createRequest(ActorInfo actorInfo, String bucketName, String fileName) {
    Request request = new Request();
    File file = new File();
    file.setBucket(bucketName);
    file.setKey(fileName);
    request.setName(actorInfo.getName());
    request.setImage(file);
    request.setQueryTimeStamp(new Date());
    return request;
  }

  private void persistRequests(List<Request> requests) {
    SessionFactory s = HibernateUtil.getSessionFactory();
    Session session = s.openSession();
    session.beginTransaction();
    try {
      for (Request request : requests) {
        File fileFromDB = getFileFromDB(request.getImage().getBucket(), request.getImage().getKey(), session);
        if (fileFromDB != null) {
          request.setImage(fileFromDB);
        } else {
          session.persist(request.getImage());
        }
        session.persist(request);
      }
    } finally {
      session.getTransaction().commit();
      session.close();
    }
  }

  private File getFileFromDB(String bucketName, String fileName, Session session) {
    CriteriaBuilder builder = session.getCriteriaBuilder();
    CriteriaQuery<File> query = builder.createQuery(File.class);
    Root<File> root = query.from(File.class);
    query.select(root).where(builder.and(builder.equal(root.get("bucket"), bucketName), builder.equal(root.get("key"), fileName)));
    Query<File> q = session.createQuery(query);
    try {
      return q.getSingleResult();
    } catch (NoResultException e) {
      return null;
    }
  }

  /**
   * Determine image format from image bytes
   *
   * @param imageBytes the image bytes
   * @return image format
   */
  private String determineImageFormat(byte[] imageBytes) {
    try {
      Iterator<ImageReader> imageReaders = ImageIO.getImageReaders(ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes)));
      if (imageReaders.hasNext()) {
        ImageReader reader = imageReaders.next();
        return reader.getFormatName();
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }
}
