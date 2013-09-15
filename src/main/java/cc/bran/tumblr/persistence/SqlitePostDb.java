package cc.bran.tumblr.persistence;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.Instant;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;
import cc.bran.tumblr.types.AnswerPost;
import cc.bran.tumblr.types.AudioPost;
import cc.bran.tumblr.types.ChatPost;
import cc.bran.tumblr.types.LinkPost;
import cc.bran.tumblr.types.PhotoPost;
import cc.bran.tumblr.types.Post;
import cc.bran.tumblr.types.PostType;
import cc.bran.tumblr.types.QuotePost;
import cc.bran.tumblr.types.TextPost;
import cc.bran.tumblr.types.VideoPost;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/**
 * Persists {@link Post}s using an SQLite backend.
 * 
 * @author Brandon Pitman (brandon.pitman@gmail.com)
 */
// TODO(bpitman): don't re-prepare statements with every call to get()/post()/etc
public class SqlitePostDb implements PostDb, AutoCloseable {

  /**
   * Represents a transaction that can be executed.
   * 
   * @author Brandon Pitman (brandon.pitman@gmail.com)
   * @param <E>
   *          the type that is returned from the transaction
   * @param <Ex>
   *          the type that is thrown from the transaction
   */
  private abstract class Transaction<E, Ex extends Exception> {

    /**
     * Executes the transaction, committing if the code returns without throwing an exception, and
     * rolling back if the function throws an exception.
     * 
     * If the attempt to rollback throws an exception, it will be included in the suppressed
     * exceptions for the thrown exception.
     * 
     * @return the value that is returned from the transaction code
     * @throws Ex
     *           if the transaction code throws an exception
     * @throws SQLException
     *           if the attempt to commit throws an exception
     */
    public E execute() throws Ex, SQLException {
      E result;

      try {
        result = runTransaction();
        connection.commit();
      } catch (Exception exception) {
        try {
          connection.rollback();
        } catch (SQLException sqlException) {
          exception.addSuppressed(sqlException);
        }
        throw exception;
      }

      return result;
    }

    /**
     * Runs the code in the transaction. This code can assume that it is inside of a transaction.
     */
    abstract E runTransaction() throws Ex;
  }

  private static final String ANSWER_POST_INSERT_SQL = "INSERT INTO answerPosts (id, askingName, askingUrl, question, answer) VALUES (?, ?, ?, ?, ?);";

  private static final String ANSWER_POSTS_REQUEST_SQL_TEMPLATE = "SELECT id, askingName, askingUrl, question, answer FROM answerPosts WHERE id IN (%s);";

  private static final String DELETE_ANSWER_POSTS_SQL_TEMPLATE = "DELETE FROM answerPosts WHERE id IN (%s)";

  private static final String DELETE_POST_TAGS_SQL_TEMPLATE = "DELETE FROM postTags WHERE postId IN (%s);";

  private static final String DELETE_POSTS_SQL_TEMPLATE = "DELETE FROM posts WHERE id IN (%s)";

  private static final String DELETE_TEXT_POSTS_SQL_TEMPLATE = "DELETE FROM textPosts WHERE id IN (%s);";

  private static final String POST_INSERT_SQL = "INSERT INTO posts (id, blogName, postUrl, postedTimestamp, retrievedTimestamp, postTypeId) SELECT ?, ?, ?, ?, ?, id FROM postTypes WHERE type = ?;";

  private static final String POST_REQUEST_SQL = "SELECT posts.id, posts.blogName, posts.postUrl, posts.postedTimestamp, posts.retrievedTimestamp, postTypes.type FROM posts JOIN postTypes ON posts.postTypeId = postTypes.id WHERE posts.id = ?;";

  private static final String POST_TAG_INSERT_SQL = "INSERT INTO postTags (postId, tagId, tagIndex) VALUES (?, ?, ?);";

  private static final String TAG_INSERT_SQL = "INSERT INTO tags (tag) VALUES (?);";

  private static final String TAG_REQUEST_BY_NAME_SQL_TEMPLATE = "SELECT id, tag FROM tags WHERE tag IN (%s);";

  private static final String TAGS_REQUEST_SQL_TEMPLATE = "SELECT postTags.postId, tags.tag FROM postTags JOIN tags ON postTags.tagId = tags.id WHERE postTags.postId IN (%s) ORDER BY postTags.tagIndex;";

  private static final String TEXT_POST_INSERT_SQL = "INSERT INTO textPosts (id, title, body) VALUES (?, ?, ?);";

  private static final String TEXT_POST_REQUEST_SQL_TEMPLATE = "SELECT id, title, body FROM textPosts WHERE id IN (%s);";

  static {
    try {
      Class.forName("org.sqlite.JDBC");
    } catch (ClassNotFoundException exception) {
      throw new AssertionError("org.sqlite.JDBC must be available", exception);
    }
  }

  private final PreparedStatement answerPostInsertStatement;

  private final Connection connection;

  private final PreparedStatement postInsertStatement;

  private final PreparedStatement postRequestStatement;

  private final PreparedStatement postTagInsertStatement;

  private final PreparedStatement tagInsertStatement;

  private final PreparedStatement textPostInsertStatement;

  @VisibleForTesting
  SqlitePostDb(Connection connection) throws SQLException {
    this.connection = connection;
    initConnection();

    answerPostInsertStatement = connection.prepareStatement(ANSWER_POST_INSERT_SQL);
    postRequestStatement = connection.prepareStatement(POST_REQUEST_SQL);
    postInsertStatement = connection.prepareStatement(POST_INSERT_SQL);
    postTagInsertStatement = connection.prepareStatement(POST_TAG_INSERT_SQL);
    tagInsertStatement = connection.prepareStatement(TAG_INSERT_SQL);
    textPostInsertStatement = connection.prepareStatement(TEXT_POST_INSERT_SQL);
  }

  public SqlitePostDb(String dbFile) throws ClassNotFoundException, SQLException {
    this(DriverManager.getConnection(String.format("jdbc:sqlite:%s", new File(dbFile).getPath())));
  }

  @Override
  public void close() throws SQLException {
    answerPostInsertStatement.close();
    postRequestStatement.close();
    postInsertStatement.close();
    postTagInsertStatement.close();
    tagInsertStatement.close();
    textPostInsertStatement.close();
  }

  @Override
  public void delete(final long id) throws SQLException {
    new Transaction<Void, SQLException>() {

      @Override
      Void runTransaction() throws SQLException {
        doDelete(ImmutableList.of(id));
        return null;
      }
    }.execute();
  }

  public void doDelete(Collection<Long> ids) throws SQLException {
    // Delete answer post-related data.
    String deleteAnswerPostsSql = String.format(DELETE_ANSWER_POSTS_SQL_TEMPLATE,
            buildInQuery(ids.size()));
    try (PreparedStatement deleteAnswerPostsStatement = connection
            .prepareStatement(deleteAnswerPostsSql)) {
      int index = 1;
      for (long id : ids) {
        deleteAnswerPostsStatement.setLong(index++, id);
      }
      deleteAnswerPostsStatement.execute();
    }

    // Delete text post-related data.
    String deleteTextPostsSql = String.format(DELETE_TEXT_POSTS_SQL_TEMPLATE,
            buildInQuery(ids.size()));
    try (PreparedStatement deleteTextPostsStatement = connection
            .prepareStatement(deleteTextPostsSql)) {
      int index = 1;
      for (long id : ids) {
        deleteTextPostsStatement.setLong(index++, id);
      }
      deleteTextPostsStatement.execute();
    }

    // Delete tag-related data.
    String deletePostTagsSql = String.format(DELETE_POST_TAGS_SQL_TEMPLATE,
            buildInQuery(ids.size()));
    try (PreparedStatement deletePostTagsStatement = connection.prepareStatement(deletePostTagsSql)) {
      int index = 1;
      for (long id : ids) {
        deletePostTagsStatement.setLong(index++, id);
      }
      deletePostTagsStatement.execute();
    }

    // Delete post-related data.
    String deletePostsSql = String.format(DELETE_POSTS_SQL_TEMPLATE, buildInQuery(ids.size()));
    try (PreparedStatement deletePostsStatement = connection.prepareStatement(deletePostsSql)) {
      int index = 1;
      for (long id : ids) {
        deletePostsStatement.setLong(index++, id);
      }
      deletePostsStatement.execute();
    }
  }

  private Post doGet(long id) throws SQLException {
    postRequestStatement.setLong(1, id);
    try (ResultSet resultSet = postRequestStatement.executeQuery()) {
      List<Post> postList = doGetFromResultSet(resultSet);

      if (postList.isEmpty()) {
        return null;
      }

      return postList.get(0);
    }
  }

  private void doGetAnswerPostData(Map<Long, AnswerPost.Builder> builderById) throws SQLException {
    if (builderById.isEmpty()) {
      return;
    }

    String answerPostRequestSql = String.format(ANSWER_POSTS_REQUEST_SQL_TEMPLATE,
            buildInQuery(builderById.size()));
    try (PreparedStatement answerPostRequestStatement = connection
            .prepareStatement(answerPostRequestSql)) {
      int index = 1;
      for (long id : builderById.keySet()) {
        answerPostRequestStatement.setLong(index++, id);
      }

      try (ResultSet resultSet = answerPostRequestStatement.executeQuery()) {
        while (resultSet.next()) {
          AnswerPost.Builder postBuilder = builderById.get(resultSet.getLong("id"));
          postBuilder.setAskingName(resultSet.getString("askingName"));
          postBuilder.setAskingUrl(resultSet.getString("askingUrl"));
          postBuilder.setQuestion(resultSet.getString("question"));
          postBuilder.setAnswer(resultSet.getString("answer"));
        }
      }
    }
  }

  private List<Post> doGetFromResultSet(ResultSet resultSet) throws SQLException {
    Map<Long, Post.Builder> builderById = new HashMap<>();
    Map<Long, AnswerPost.Builder> answerBuilderById = new HashMap<>();
    Map<Long, TextPost.Builder> textBuilderById = new HashMap<>();

    // Extract basic data from results & categorize them by type.
    while (resultSet.next()) {
      Post.Builder postBuilder;
      long id = resultSet.getLong("id");
      PostType postType = PostType.valueOf(resultSet.getString("type"));

      switch (postType) {
      case ANSWER:
        postBuilder = new AnswerPost.Builder();
        answerBuilderById.put(id, (AnswerPost.Builder) postBuilder);
        break;
      case AUDIO:
        postBuilder = new AudioPost.Builder();
        throw new NotImplementedException();
      case CHAT:
        postBuilder = new ChatPost.Builder();
        throw new NotImplementedException();
      case LINK:
        postBuilder = new LinkPost.Builder();
        throw new NotImplementedException();
      case PHOTO:
        postBuilder = new PhotoPost.Builder();
        throw new NotImplementedException();
      case QUOTE:
        postBuilder = new QuotePost.Builder();
        throw new NotImplementedException();
      case TEXT:
        postBuilder = new TextPost.Builder();
        textBuilderById.put(id, (TextPost.Builder) postBuilder);
        break;
      case VIDEO:
        postBuilder = new VideoPost.Builder();
        throw new NotImplementedException();
      default:
        throw new AssertionError(String.format("Post %d has impossible type %s.", id,
                postType.toString()));
      }

      // Set basic data.
      postBuilder.setId(id);
      postBuilder.setBlogName(resultSet.getString("blogName"));
      postBuilder.setPostUrl(resultSet.getString("postUrl"));
      postBuilder.setPostedInstant(new Instant(resultSet.getLong("postedTimestamp")));
      postBuilder.setRetrievedInstant(new Instant(resultSet.getLong("retrievedTimestamp")));

      builderById.put(id, postBuilder);
    }

    // Set tag data & post type-specific data.
    doGetTagData(builderById);
    doGetAnswerPostData(answerBuilderById);
    doGetTextPostData(textBuilderById);

    // Build result.
    ImmutableList.Builder<Post> resultBuilder = ImmutableList.builder();
    for (Post.Builder postBuilder : builderById.values()) {
      resultBuilder.add(postBuilder.build());
    }
    return resultBuilder.build();
  }

  private void doGetTagData(Map<Long, Post.Builder> builderById) throws SQLException {
    if (builderById.isEmpty()) {
      return;
    }

    // Prepare data structures.
    Map<Long, ImmutableList.Builder<String>> tagListBuilderById = new HashMap<>();
    for (long id : builderById.keySet()) {
      tagListBuilderById.put(id, new ImmutableList.Builder<String>());
    }

    // Request tags & parse data into structure.
    String tagRequestSql = String.format(TAGS_REQUEST_SQL_TEMPLATE,
            buildInQuery(builderById.size()));
    try (PreparedStatement tagRequestStatement = connection.prepareStatement(tagRequestSql)) {
      int index = 1;
      for (long id : builderById.keySet()) {
        tagRequestStatement.setLong(index++, id);
      }

      try (ResultSet resultSet = tagRequestStatement.executeQuery()) {
        while (resultSet.next()) {
          long id = resultSet.getLong("postId");
          String tag = resultSet.getString("tag");

          tagListBuilderById.get(id).add(tag);
        }
      }
    }

    // Place parsed tags into post builders.
    for (Map.Entry<Long, Post.Builder> entry : builderById.entrySet()) {
      long id = entry.getKey();
      Post.Builder postBuilder = entry.getValue();

      postBuilder.setTags(tagListBuilderById.get(id).build());
    }
  }

  private void doGetTextPostData(Map<Long, TextPost.Builder> builderById) throws SQLException {
    if (builderById.isEmpty()) {
      return;
    }

    // Request text post data & place into post builders.
    String textPostRequestSql = String.format(TEXT_POST_REQUEST_SQL_TEMPLATE,
            buildInQuery(builderById.size()));
    try (PreparedStatement textPostRequestStatement = connection
            .prepareStatement(textPostRequestSql)) {
      int index = 1;
      for (long id : builderById.keySet()) {
        textPostRequestStatement.setLong(index++, id);
      }

      try (ResultSet resultSet = textPostRequestStatement.executeQuery()) {
        while (resultSet.next()) {
          TextPost.Builder builder = builderById.get(resultSet.getLong("id"));
          builder.setTitle(resultSet.getString("title"));
          builder.setBody(resultSet.getString("body"));
        }
      }
    }
  }

  private void doPut(Collection<Post> posts) throws SQLException {
    if (posts.isEmpty()) {
      return;
    }

    // Categorize post by type & update basic post information.
    Map<Long, Post> postById = new HashMap<>();
    Map<Long, AnswerPost> answerPostById = new HashMap<>();
    Map<Long, TextPost> textPostById = new HashMap<>();

    for (Post post : posts) {
      postById.put(post.getId(), post);

      switch (post.getType()) {
      case ANSWER:
        answerPostById.put(post.getId(), (AnswerPost) post);
        break;
      case AUDIO:
        throw new NotImplementedException();
      case CHAT:
        throw new NotImplementedException();
      case LINK:
        throw new NotImplementedException();
      case PHOTO:
        throw new NotImplementedException();
      case QUOTE:
        throw new NotImplementedException();
      case TEXT:
        textPostById.put(post.getId(), (TextPost) post);
        break;
      case VIDEO:
        throw new NotImplementedException();
      default:
        throw new AssertionError(String.format("Post %d has impossible type %s.", post.getId(),
                post.getType().toString()));
      }
    }

    // Delete existing post information.
    doDelete(postById.keySet());

    // Update basic post information.
    for (Post post : posts) {
      postInsertStatement.setLong(1, post.getId());
      postInsertStatement.setString(2, post.getBlogName());
      postInsertStatement.setString(3, post.getPostUrl());
      postInsertStatement.setLong(4, post.getPostedInstant().getMillis());
      postInsertStatement.setLong(5, post.getRetrievedInstant().getMillis());
      postInsertStatement.setString(6, post.getType().toString());
      postInsertStatement.addBatch();
    }
    postInsertStatement.executeBatch();

    // Update tag & post-type specific information.
    doPutTagData(postById);
    doPutAnswerPostData(answerPostById);
    doPutTextPostData(textPostById);
  }

  private void doPutAnswerPostData(Map<Long, AnswerPost> postById) throws SQLException {
    if (postById.isEmpty()) {
      return;
    }

    for (AnswerPost post : postById.values()) {
      answerPostInsertStatement.setLong(1, post.getId());
      answerPostInsertStatement.setString(2, post.getAskingName());
      answerPostInsertStatement.setString(3, post.getAskingUrl());
      answerPostInsertStatement.setString(4, post.getQuestion());
      answerPostInsertStatement.setString(5, post.getAnswer());
      answerPostInsertStatement.addBatch();
    }
    answerPostInsertStatement.executeBatch();
  }

  private void doPutTagData(Map<Long, Post> postById) throws SQLException {
    if (postById.isEmpty()) {
      return;
    }

    // Find IDs for existing tags.
    Map<String, Integer> idByTag = new HashMap<>();
    for (Post post : postById.values()) {
      for (String tag : post.getTags()) {
        idByTag.put(tag, null);
      }
    }

    if (idByTag.isEmpty()) {
      return;
    }

    String tagRequestByNameSql = String.format(TAG_REQUEST_BY_NAME_SQL_TEMPLATE,
            buildInQuery(idByTag.size()));
    try (PreparedStatement tagRequestByNameStatement = connection
            .prepareStatement(tagRequestByNameSql)) {
      int index = 1;
      for (String tag : idByTag.keySet()) {
        tagRequestByNameStatement.setString(index++, tag);
      }

      try (ResultSet resultSet = tagRequestByNameStatement.executeQuery()) {
        while (resultSet.next()) {
          int id = resultSet.getInt("id");
          String tag = resultSet.getString("tag");

          idByTag.put(tag, id);
        }
      }
    }

    // Create missing tags, if any.
    for (Map.Entry<String, Integer> entry : idByTag.entrySet()) {
      if (entry.getValue() != null) {
        continue;
      }

      String tag = entry.getKey();
      tagInsertStatement.setString(1, tag);
      tagInsertStatement.execute();
      try (ResultSet resultSet = tagInsertStatement.getGeneratedKeys()) {
        int id = resultSet.getInt(1);
        entry.setValue(id);
      }
    }

    // Update the postTags table.
    for (Post post : postById.values()) {
      int index = 0;
      for (String tag : post.getTags()) {
        postTagInsertStatement.setLong(1, post.getId());
        postTagInsertStatement.setInt(2, idByTag.get(tag));
        postTagInsertStatement.setInt(3, index++);
        postTagInsertStatement.addBatch();
      }
    }
    postTagInsertStatement.executeBatch();
  }

  private void doPutTextPostData(Map<Long, TextPost> postById) throws SQLException {
    if (postById.isEmpty()) {
      return;
    }

    for (TextPost post : postById.values()) {
      textPostInsertStatement.setLong(1, post.getId());
      textPostInsertStatement.setString(2, post.getTitle());
      textPostInsertStatement.setString(3, post.getBody());
      textPostInsertStatement.addBatch();
    }
    textPostInsertStatement.executeBatch();
  }

  @Override
  public Post get(final long id) throws SQLException {
    return new Transaction<Post, SQLException>() {

      @Override
      Post runTransaction() throws SQLException {
        return doGet(id);
      }
    }.execute();
  }

  private void initConnection() throws SQLException {
    connection.setAutoCommit(false);

    new Transaction<Void, SQLException>() {

      @Override
      Void runTransaction() throws SQLException {
        try (Statement statement = connection.createStatement()) {
          statement.execute("PRAGMA foreign_keys = ON;");

          // Main post tables.
          statement
                  .execute("CREATE TABLE IF NOT EXISTS posts(id INTEGER PRIMARY KEY, blogName TEXT NOT NULL, postUrl TEXT NOT NULL, postedTimestamp INTEGER NOT NULL, retrievedTimestamp INTEGER NOT NULL, postTypeId INTEGER NOT NULL REFERENCES postTypes(id));");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS textPosts(id INTEGER PRIMARY KEY REFERENCES posts(id), title TEXT NOT NULL, body TEXT NOT NULL);");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS photoPosts(id INTEGER PRIMARY KEY REFERENCES posts(id), caption TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL);");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS quotePosts(id INTEGER PRIMARY KEY REFERENCES posts(id), text TEXT NOT NULL, source TEXT NOT NULL);");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS linkPosts(id INTEGER PRIMARY KEY REFERENCES posts(id), title TEXT NOT NULL, url TEXT NOT NULL, description TEXT NOT NULL);");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS chatPosts(id INTEGER PRIMARY KEY REFERENCES posts(id), title TEXT NOT NULL, body TEXT NOT NULL);");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS audioPosts(id INTEGER PRIMARY KEY REFERENCES posts(id), caption TEXT NOT NULL, player TEXT NOT NULL, plays INTEGER NOT NULL, albumArt TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, trackName TEXT NOT NULL, trackNumber INTEGER NOT NULL, year INTEGER NOT NULL);");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS videoPosts(id INTEGER PRIMARY KEY REFERENCES posts(id), caption TEXT NOT NULL);");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS answerPosts(id INTEGER PRIMARY KEY REFERENCES posts(id), askingName TEXT NOT NULL, askingUrl TEXT NOT NULL, question TEXT NOT NULL, answer TEXT NOT NULL);");

          // Tags tables.
          statement
                  .execute("CREATE TABLE IF NOT EXISTS tags(id INTEGER PRIMARY KEY AUTOINCREMENT, tag TEXT UNIQUE NOT NULL);");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS postTags(postId INTEGER NOT NULL REFERENCES posts(id), tagId INTEGER NOT NULL REFERENCES tags(id), tagIndex INTEGER NOT NULL, PRIMARY KEY(postId, tagId));");

          // Photo post-specific tables.
          statement
                  .execute("CREATE TABLE IF NOT EXISTS photos(id INTEGER PRIMARY KEY AUTOINCREMENT, caption TEXT NOT NULL);");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS photoSizes(id INTEGER PRIMARY KEY AUTOINCREMENT, width INTEGER NOT NULL, height INTEGER NOT NULL, url TEXT NOT NULL);");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS photoPostPhotos(postId INTEGER NOT NULL REFERENCES photoPosts(id), photoId INTEGER NOT NULL REFERENCES photos(id), photoIndex INTEGER NOT NULL, PRIMARY KEY(postId, photoId));");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS photoPhotoSizes(photoId INTEGER NOT NULL REFERENCES photos(id), photoSizeId INTEGER NOT NULL REFERENCES photoSizes(id), photoSizeIndex INTEGER NOT NULL, PRIMARY KEY(photoId, photoSizeId));");

          // Chat post-specific tables.
          statement
                  .execute("CREATE TABLE IF NOT EXISTS dialogue(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, label TEXT NOT NULL, phrase TEXT NOT NULL);");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS chatPostDialogue(postId INTEGER NOT NULL REFERENCES chatPosts(id), dialogueId INTEGER NOT NULL REFERENCES dialogue(id), dialogueIndex INTEGER NOT NULL, PRIMARY KEY(postId, dialogueId));");

          // Video post-specific tables.
          statement
                  .execute("CREATE TABLE IF NOT EXISTS players(id INTEGER PRIMARY KEY AUTOINCREMENT, width TEXT NOT NULL, embedCode TEXT NOT NULL);");
          statement
                  .execute("CREATE TABLE IF NOT EXISTS videoPostPlayers(postId INTEGER NOT NULL REFERENCES videoPosts(id), playerId INTEGER NOT NULL REFERENCES players(id), playerIndex INTEGER NOT NULL, PRIMARY KEY(postId, playerId));");

          // Types table.
          statement
                  .execute("CREATE TABLE IF NOT EXISTS postTypes(id INTEGER PRIMARY KEY AUTOINCREMENT, type STRING UNIQUE NOT NULL);");
          try (PreparedStatement typeInsertStatement = connection
                  .prepareStatement("INSERT OR IGNORE INTO postTypes (type) VALUES (?)")) {
            for (PostType type : PostType.values()) {
              typeInsertStatement.setString(1, type.toString());
              typeInsertStatement.addBatch();
            }
            typeInsertStatement.executeBatch();
          }

          // Indexes.
          statement
                  .execute("CREATE INDEX IF NOT EXISTS postsPostTypeIdIndex ON posts(postTypeId);");
          statement.execute("CREATE INDEX IF NOT EXISTS postTagsPostIdIndex ON postTags(postId);");
          statement.execute("CREATE INDEX IF NOT EXISTS postTagsTagIdIndex ON postTags(tagId);");
          statement.execute("CREATE INDEX IF NOT EXISTS tagsTagIndex ON tags(tag);");
          statement
                  .execute("CREATE INDEX IF NOT EXISTS photoPostPhotosPostIdIndex ON photoPostPhotos(postId);");
          statement
                  .execute("CREATE INDEX IF NOT EXISTS photoPostPhotosPhotoIdIndex ON photoPostPhotos(photoId);");
          statement
                  .execute("CREATE INDEX IF NOT EXISTS photoPhotoSizesPhotoIdIndex ON photoPhotoSizes(photoId);");
          statement
                  .execute("CREATE INDEX IF NOT EXISTS photoPhotoSizesPhotoSizeIdIndex ON photoPhotoSizes(photoSizeId);");
          statement
                  .execute("CREATE INDEX IF NOT EXISTS chatPostDialoguePostIdIndex ON chatPostDialogue(postId);");
          statement
                  .execute("CREATE INDEX IF NOT EXISTS chatPostDialogueDialogueIdIndex ON chatPostDialogue(dialogueId);");
          statement
                  .execute("CREATE INDEX IF NOT EXISTS videoPostPlayersPostIdIndex ON videoPostPlayers(postId);");
          statement
                  .execute("CREATE INDEX IF NOT EXISTS videoPostPlayersPlayerIdIndex ON videoPostPlayers(playerId);");
          statement
                  .execute("CREATE UNIQUE INDEX IF NOT EXISTS postTypesTypeIndex ON postTypes(type);");

          return null;
        }
      }
    }.execute();
  }

  @Override
  public void put(final Post post) throws SQLException {
    new Transaction<Void, SQLException>() {

      @Override
      Void runTransaction() throws SQLException {
        doPut(ImmutableList.of(post));
        return null;
      }
    }.execute();
  }

  private static String buildInQuery(int numItemsInSet) {
    Preconditions.checkArgument(numItemsInSet > 0);
    StringBuilder builder = new StringBuilder("?");
    while (--numItemsInSet > 0) {
      builder.append(", ?");
    }
    return builder.toString();
  }
}
