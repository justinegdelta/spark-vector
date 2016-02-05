package com.actian.spark_vectorh.vector

import java.sql._
import java.util.Properties

import com.actian.spark_vectorh.util.ResourceUtil
import com.actian.spark_vectorh.vector.ErrorCodes._
import org.apache.spark.Logging

/**
 * Encapsulate functions for accessing Vector using JDBC
 */
class VectorJDBC(cxnProps: VectorConnectionProperties) extends Logging {

  import resource._
  import ResourceUtil._

  private val tableNameDelimiter = "\""

  private val dbCxn: Connection = {
    val props = new Properties()
    cxnProps.user.foreach(props.setProperty("user", _))
    cxnProps.password.foreach(props.setProperty("password", _))
    DriverManager.getConnection(cxnProps.toJdbcUrl, props)
  }

  /** Execute a `JDBC` statement closing resources on failures, using scala-arm's `resource` package */
  def withStatement[T](op: Statement => T): T = {
    managed(dbCxn.createStatement()).map(op).resolve()
  }

  /**
   * Execute a `SQL` query closing resources on failures, using scala-arm's `resource` package,
   * mapping the `ResultSet` to a new type as specified by `op`
   */
  def executeQuery[T](sql: String)(op: ResultSet => T): T = {
    withStatement(statement => managed(statement.executeQuery(sql)).map(op)).resolve()
  }

  /** Execute the update `SQL` query specified by `sql` */
  def executeStatement(sql: String): Int = {
    withStatement(_.executeUpdate(sql))
  }

  /** Return true if there is a table named `tableName` in Vector(H) */
  def tableExists(tableName: String): Boolean = {
    if (!tableName.isEmpty) {
      val sql = s"SELECT COUNT(*) FROM ${tableNameDelimiter}${tableName}${tableNameDelimiter} WHERE 1=0"
      try {
        executeQuery(sql)(_ != null)
      } catch {
        case exc: Exception =>
          val message = exc.getLocalizedMessage // FIXME check for english text on localized message...?
          if (!message.contains("does not exist or is not owned by you")) {
            throw new VectorException(noSuchTable , s"SQL exception encountered while checking for existence of table ${tableName}: ${message}", exc)
          } else {
            false
          }
      }
    } else {
      false
    }
  }

  /**
   * Retrieve the `ColumnMetadata`s for table `tableName` as a sequence containing as many elements
   * as there are columns in the table. Each element contains the name, type, nullability, precision
   * and scale of its corresponding column in `tableName`
   */
  def columnMetadata(tableName: String): Seq[ColumnMetadata] = {
    val sql = s"SELECT * FROM ${tableName}  WHERE 1=0"
    try {
      executeQuery(sql)(resultSet => {
        val metaData = resultSet.getMetaData
        for (columnIndex <- 1 to metaData.getColumnCount) yield {
          new ColumnMetadata(
            metaData.getColumnName(columnIndex),
            metaData.getColumnTypeName(columnIndex),
            metaData.isNullable(columnIndex) == 1,
            metaData.getPrecision(columnIndex),
            metaData.getScale(columnIndex))
        }
      })
    } catch {
      case exc: Exception => throw new VectorException(noSuchTable , s"Unable to query target table '${tableName}': ${exc.getLocalizedMessage}", exc)
    }
  }

  /** Parse the result of a JDBC statement as at most a single value (possibly none) */
  def querySingleResult[T](sql: String): Option[T] = {
    executeQuery(sql)(resultSet => {
      if (resultSet.next()) {
        Option(resultSet.getObject(1).asInstanceOf[T])
      } else {
        None
      }
    })
  }

  private class ResultSetIterator[T](result: ResultSet)(extractor: ResultSet => T) extends Iterator[T] {
    override def hasNext: Boolean = result.next()
    override def next(): T = extractor(result)
  }

  /** Execute a select query on Vector and return the results as a matrix of elements */
  def query(sql: String): Seq[Seq[Any]] = {
    def toRow(numColumns: Int)(result: ResultSet): Seq[Any] =
      (1 to numColumns).map(result.getObject)
    executeQuery(sql)(resultSet => {
      val numColumns = resultSet.getMetaData.getColumnCount
      new ResultSetIterator(resultSet)(toRow(numColumns)).toVector
    })
  }

  /** Drop the Vector(H) table `tableName` if it exists */
  def dropTable(tableName: String): Unit = {
    try {
      executeStatement(s"drop table if exists ${tableName}")
    } catch {
      case exc: Exception => throw new VectorException(sqlException, s"Unable to drop table '${tableName}'", exc)
    }
  }

  /** Return true if the table `tableName` is empty */
  def isTableEmpty(tableName: String): Boolean = {
    val rowCount = querySingleResult[Int](s"select count(*) from ${tableName}")
    rowCount.map(_ == 0).getOrElse(throw new VectorException(sqlException, s"No result for count on table '${tableName}'"))
  }

  
  /** Close the Vector(H) `JDBC` connection */
  def close(): Unit = dbCxn.close

  /** Set auto-commit to ON/OFF for this `JDBC` connection */
  def autoCommit(value: Boolean): Unit = {
    dbCxn.setAutoCommit(value)
  }

  /** Commit the last transaction */
  def commit(): Unit = dbCxn.commit()

  /** Rollback the last transaction */
  def rollback(): Unit = dbCxn.rollback()

  /** Return the hostname of where the ingres frontend of Vector is located (usually on the leader node) */
  def getIngresHost(): String = cxnProps.host
}

object VectorJDBC extends Logging {

  import resource._
  import ResourceUtil._

  /** Create a `VectorJDBC`, execute the statements specified by `op` and close the `JDBC` connections when finished */
  def withJDBC[T](cxnProps: VectorConnectionProperties)(op: VectorJDBC => T): T = {
    managed(new VectorJDBC(cxnProps)).map(op).resolve()
  }

  /**
   * Run the given sequence of SQL statements in order. No results are returned.
   * A failure will cause any changes to be rolled back. An empty set of statements
   * is ignored.
   *
   * @param vectorProps connection properties
   * @param statements sequence of SQL statements to execute
   */
  def executeStatements(vectorProps: VectorConnectionProperties)(statements: Seq[String]): Unit = {
    withJDBC(vectorProps) { cxn =>
      // Turn auto-commit off. Want to commit once all statements are run.
      cxn.autoCommit(false)

      // Execute each SQL statement
      statements.foreach(statement =>
        try {
          cxn.executeStatement(statement)
        } catch {
          case exc: Exception =>
            cxn.rollback()
            logError(s"error executing SQL statement: '${statement}'", exc)
            throw VectorException(sqlExecutionError, s"Error executing SQL statement: '${statement}'", cause = exc)
        })
      // Commit since all SQL statements ran OK
      cxn.commit()
    }
  }
}
