package com.maxdemarzi.users;

import com.maxdemarzi.Labels;
import com.maxdemarzi.RelationshipTypes;
import com.maxdemarzi.posts.PostExceptions;
import org.apache.shiro.crypto.hash.Md5Hash;
import org.codehaus.jackson.map.ObjectMapper;
import org.neo4j.graphdb.*;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.maxdemarzi.Properties.*;
import static java.util.Collections.reverseOrder;

@Path("/users")
public class Users {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final ZoneId utc = TimeZone.getTimeZone("UTC").toZoneId();
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter
            .ofPattern("yyyy_MM_dd")
            .withZone(utc);

    @GET
    @Path("/{username}")
    public Response getUser(@PathParam("username") final String username, @Context GraphDatabaseService db) throws IOException {
        Map<String, Object> results;
        try (Transaction tx = db.beginTx()) {
            Node user = findUser(username, db);
            results = user.getAllProperties();
            tx.success();
        }
        return Response.ok().entity(objectMapper.writeValueAsString(results)).build();
    }

    @GET
    @Path("/{username}/profile")
    public Response getProfile(@PathParam("username") final String username, @Context GraphDatabaseService db) throws IOException {
        Map<String, Object> results;
        try (Transaction tx = db.beginTx()) {
            Node user = findUser(username, db);
            results = getUserAttributes(user);
            tx.success();
        }
        return Response.ok().entity(objectMapper.writeValueAsString(results)).build();
    }

    @POST
    public Response createUser(String body, @Context GraphDatabaseService db) throws IOException {
        HashMap parameters = UserValidator.validate(body);
        Map<String, Object> results;
        try (Transaction tx = db.beginTx()) {
            Node user = db.findNode(Labels.User, USERNAME, parameters.get(USERNAME));
            if (user == null) {
                user = db.findNode(Labels.User, EMAIL, parameters.get(EMAIL));
                if (user == null) {
                    user = db.createNode(Labels.User);
                    user.setProperty(EMAIL, parameters.get(EMAIL));
                    user.setProperty(NAME, parameters.get(NAME));
                    user.setProperty(USERNAME, parameters.get(USERNAME));
                    user.setProperty(PASSWORD, parameters.get(PASSWORD));
                    user.setProperty(HASH, new Md5Hash(((String)parameters.get(EMAIL)).toLowerCase()).toString());
                    results = user.getAllProperties();
                } else {
                    throw UserExceptions.existingEmailParameter;
                }
            } else {
                throw UserExceptions.existingUsernameParameter;
            }
            tx.success();
        }
        return Response.ok().entity(objectMapper.writeValueAsString(results)).build();
    }

    @GET
    @Path("/{username}/followers")
    public Response getFollowers(@PathParam("username") final String username, @Context GraphDatabaseService db) throws IOException {
        ArrayList<Map<String, Object>> results = new ArrayList<>();
        try (Transaction tx = db.beginTx()) {
            Node user = findUser(username, db);
            for (Relationship r1: user.getRelationships(Direction.INCOMING, RelationshipTypes.FOLLOWS)) {
                Node follower = r1.getStartNode();
                Long time = (Long)r1.getProperty(TIME);
                Map<String, Object> result = getUserAttributes(follower);
                result.put(TIME, time);
                results.add(result);
            }
            tx.success();
        }
        results.sort(Comparator.comparing(m -> (Long) m.get(TIME), reverseOrder()));
        return Response.ok().entity(objectMapper.writeValueAsString(results)).build();
    }

    @GET
    @Path("/{username}/following")
    public Response getFollowing(@PathParam("username") final String username, @Context GraphDatabaseService db) throws IOException {
        ArrayList<Map<String, Object>> results = new ArrayList<>();
        try (Transaction tx = db.beginTx()) {
            Node user = findUser(username, db);
            for (Relationship r1: user.getRelationships(Direction.OUTGOING, RelationshipTypes.FOLLOWS)) {
                Node following = r1.getEndNode();
                Long time = (Long)r1.getProperty(TIME);
                Map<String, Object> result = getUserAttributes(following);
                result.put(TIME, time);
                results.add(result);
            }
            tx.success();
        }
        results.sort(Comparator.comparing(m -> (Long) m.get(TIME), reverseOrder()));
        return Response.ok().entity(objectMapper.writeValueAsString(results)).build();
    }

    @POST
    @Path("/{username}/follows/{username2}")
    public Response createFollows(@PathParam("username") final String username,
                                 @PathParam("username2") final String username2,
                                 @Context GraphDatabaseService db) throws IOException {
        Map<String, Object> results;
        try (Transaction tx = db.beginTx()) {
            Node user = findUser(username, db);
            Node user2 = findUser(username2, db);

            Relationship follows =  user.createRelationshipTo(user2, RelationshipTypes.FOLLOWS);
            LocalDateTime dateTime = LocalDateTime.now(utc);
            follows.setProperty(TIME, dateTime.toEpochSecond(ZoneOffset.UTC));
            results = user2.getAllProperties();
            results.remove(EMAIL);
            results.remove(PASSWORD);
            tx.success();
        }
        return Response.ok().entity(objectMapper.writeValueAsString(results)).build();
    }

    public static Node findUser(String username, @Context GraphDatabaseService db) {
        Node user = db.findNode(Labels.User, USERNAME, username);
        if (user == null) { throw UserExceptions.userNotFound;}
        return user;
    }

    private Map<String, Object> getUserAttributes(Node user) {
        Map<String, Object> results;
        results = user.getAllProperties();
        results.remove(EMAIL);
        results.remove(PASSWORD);
        Integer following = user.getDegree(RelationshipTypes.FOLLOWS, Direction.OUTGOING);
        Integer followers = user.getDegree(RelationshipTypes.FOLLOWS, Direction.INCOMING);
        Integer likes = user.getDegree(RelationshipTypes.LIKES, Direction.OUTGOING);
        Integer posts = user.getDegree(Direction.OUTGOING) - following - likes;
        results.put("following", following);
        results.put("followers", followers);
        results.put("likes", likes);
        results.put("posts", posts);
        return results;
    }

    public static Node getPost(Node author, Long time) {
        LocalDateTime postedDateTime = LocalDateTime.ofEpochSecond(time, 0, ZoneOffset.UTC);
        RelationshipType original = RelationshipType.withName("POSTED_ON_" +
                postedDateTime.format(dateFormatter));
        Node post = null;
        for(Relationship r1 : author.getRelationships(Direction.OUTGOING, original)) {
            Node potential = r1.getEndNode();
            if (time.equals(potential.getProperty(TIME))) {
                post = potential;
                break;
            }
        }
        if(post == null) { throw PostExceptions.postNotFound; };

        return post;
    }


}