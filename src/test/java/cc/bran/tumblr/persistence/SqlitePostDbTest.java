package cc.bran.tumblr.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.joda.time.Duration;
import org.joda.time.Instant;

import cc.bran.tumblr.types.AnswerPost;
import cc.bran.tumblr.types.AudioPost;
import cc.bran.tumblr.types.Post;
import cc.bran.tumblr.types.TextPost;

import com.google.common.collect.ImmutableList;

/**
 * Tests for {@link SqlitePostDb}.
 * 
 * @author Brandon Pitman (brandon.pitman@gmail.com)
 */
public class SqlitePostDbTest extends TestCase {

  private static final Post ANSWER_POST_1 = new AnswerPost(566, "foo.tumblr.com",
          "http://foo.tumblr.com/posts/566/whee", Instant.now().minus(Duration.millis(5100)),
          Instant.now().minus(Duration.millis(2200)), ImmutableList.of("tag1", "tag4"), "fool",
          "http://fool.tumblr.com/", "War, huh, what is it good for?", "Absolutely nothing.");

  private static final Post ANSWER_POST_1_EDITED = new AnswerPost(566, "foo.tumblr.com",
          "http://foo.tumblr.com/posts/566/whee", Instant.now().minus(Duration.millis(5100)),
          Instant.now().minus(Duration.millis(2200)), ImmutableList.of("tag1", "tag4"), "fool",
          "http://fool.tumblr.com/", "War, huh, what is it good for?", "Oil, lol.");

  private static final Post AUDIO_POST_1 = new AudioPost(414, "foo.tumblr.com",
          "http://foo.tumblr.com/414/whee", Instant.now().minus(Duration.millis(5200)),
          Instant.now(), ImmutableList.of("tag2", "tag5"), "a song", "player", 52, "album art",
          "fartist", "fartistry", "a track name", 2, 1993);

  private static final Post AUDIO_POST_1_EDITED = new AudioPost(414, "foo.tumblr.com",
          "http://foo.tumblr.com/414/whee", Instant.now().minus(Duration.millis(5200)),
          Instant.now(), ImmutableList.of("tag4", "tag5"), "edited song", "player", 52,
          "album art", "artist formerly known as fartist", "fartistry x 2", "a track name", 2, 1993);

  private static final Post TEXT_POST_1 = new TextPost(513, "foo.tumblr.com",
          "http://foo.tumblr.com/posts/513/whee", Instant.now().minus(Duration.millis(5000)),
          Instant.now(), ImmutableList.of("tag1", "tag2", "tag3"), "test post",
          "hello world, this is a test post");

  private static final Post TEXT_POST_1_EDITED = new TextPost(513, "foo.tumblr.com",
          "http://foo.tumblr.com/posts/513/whee", Instant.now().minus(Duration.millis(6000)),
          Instant.now().minus(Duration.millis(2100)), ImmutableList.of("tag2", "tag4"),
          "edited test post", "the old content was bad");

  static {
    try {
      Class.forName("org.sqlite.JDBC");
    } catch (ClassNotFoundException exception) {
      throw new AssertionError("org.sqlite.JDBC must be available", exception);
    }
  }

  private SqlitePostDb postDb;

  public SqlitePostDbTest(String testName) {
    super(testName);
  }

  public void assertCanDelete(Post post) throws SQLException {
    postDb.put(post);
    assertNotNull(postDb.get(post.getId()));
    postDb.delete(post.getId());
    assertNull(postDb.get(post.getId()));
  }

  public void assertCanEdit(Post post, Post editedPost) throws SQLException {
    assertEquals(post.getId(), editedPost.getId());
    assertFalse(post.equals(editedPost));

    postDb.put(post);
    Post retrievedPost = postDb.get(post.getId());
    assertEquals(post, retrievedPost);

    postDb.put(editedPost);
    retrievedPost = postDb.get(post.getId());
    assertEquals(editedPost, retrievedPost);

    postDb.put(post);
    retrievedPost = postDb.get(post.getId());
    assertEquals(post, retrievedPost);
  }

  public void assertCanGet(Post post) throws SQLException {
    postDb.put(post);
    Post retrievedPost = postDb.get(post.getId());
    assertEquals(post, retrievedPost);
    assertNotSame(post, retrievedPost);
  }

  public void assertCanPut(Post post) throws SQLException {
    postDb.put(post);
  }

  public void setUp() throws SQLException {
    Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    postDb = new SqlitePostDb(connection);
  }

  public void tearDown() throws SQLException {
    postDb.close();
  }

  public void testDelete_answerPost() throws SQLException {
    assertCanDelete(ANSWER_POST_1);
  }

  public void testDelete_audioPost() throws SQLException {
    assertCanDelete(AUDIO_POST_1);
  }

  public void testDelete_textPost() throws SQLException {
    assertCanDelete(TEXT_POST_1);
  }

  public void testEdit_answerPost() throws SQLException {
    assertCanEdit(ANSWER_POST_1, ANSWER_POST_1_EDITED);
  }

  public void testEdit_audioPost() throws SQLException {
    assertCanEdit(AUDIO_POST_1, AUDIO_POST_1_EDITED);
  }

  public void testEdit_textPost() throws SQLException {
    assertCanEdit(TEXT_POST_1, TEXT_POST_1_EDITED);
  }

  public void testGet_answerPost() throws SQLException {
    assertCanGet(ANSWER_POST_1);
  }

  public void testGet_audioPost() throws SQLException {
    assertCanGet(AUDIO_POST_1);
  }

  public void testGet_nonexistent() throws SQLException {
    assertNull(postDb.get(12345));
  }

  public void testGet_textPost() throws SQLException {
    assertCanGet(TEXT_POST_1);
  }

  public void testPut_answerPost() throws SQLException {
    assertCanPut(ANSWER_POST_1);
  }

  public void testPut_audioPost() throws SQLException {
    assertCanPut(AUDIO_POST_1);
  }

  public void testPut_textPost() throws SQLException {
    assertCanPut(TEXT_POST_1);
  }

  public static Test suite() {
    return new TestSuite(SqlitePostDbTest.class);
  }
}
