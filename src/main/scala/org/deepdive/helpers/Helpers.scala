package org.deepdive.helpers

import org.deepdive.Logging
import org.deepdive.settings._
import org.deepdive.datastore.JdbcDataStore
import java.io._
import scala.sys.process._
import scala.util.{Try, Success, Failure}

/** Some helper functions that are used in many places 
 */
object Helpers extends Logging {
  
  // Constants
  val Psql = "psql"
  val Mysql = "mysql"
  val Impala = "impala"
  val Hive = "hive"

  val PsqlDriver = "org.postgresql.Driver"
  val MysqlDriver = "com.mysql.jdbc.Driver"
  val ImpalaDriver = "com.cloudera.impala.jdbc41.Driver"
  val HiveDriver = "org.apache.hive.jdbc.HiveDriver"

  /**
   * Get the dbtype from DbSettings
   * 
   * @returns 
   *     Helpers.Psql 
   *     or
   *     Helpsers.Mysql
   */
  def getDbType(dbSettings: DbSettings) : String = {
    // Branch by database driver type
    dbSettings.driver match {
      case PsqlDriver => Psql
      case MysqlDriver => Mysql
      case ImpalaDriver => Impala
      case HiveDriver => Hive
    }
  }
  
  /**
   * Return a string of psql / mysql options, including user / password / host 
   * / port / database.
   * Do not support password for psql for now.
   * 
   * TODO: using passwords via command line is poor practice. postgres support 
   * specifying password through a PGPASSFILE:
   *   http://www.postgresql.org/docs/current/static/libpq-pgpass.html
   */
  def getOptionString(dbSettings: DbSettings) : String = {
    val dbtype = getDbType(dbSettings)
    val dbname = dbSettings.dbname
    val dbuser = dbSettings.user
    val dbport = dbSettings.port
    val dbhost = dbSettings.host
    val dbpassword = dbSettings.password
      // TODO do not use password for now
    val dbnameStr = dbname match {
      case null => ""
      case _ => dbtype match {
        case Psql => s" -d ${dbname} "
        case Mysql => s" ${dbname} " // can also use -D but mysqlimport does not support -D
        case Impala => s" -d ${dbname} "
        case Hive => s""
      }
    }

    val dbuserStr = dbuser match {
      case null => ""
      case _ => dbtype match {
        case Psql => s" -U ${dbuser} "
        case Mysql => dbpassword match { // see if password is empty
          case null => s" -u ${dbuser} "
          case "" => s" -u ${dbuser} "
          case _ => s" -u ${dbuser} -p${dbpassword}"
        }
        case Impala => s" -u ${dbuser}"
        case Hive => s" -n ${dbuser}"
      }
    }
    val dbportStr = dbport match {
      case null => ""
      case _ => dbtype match {
        case Psql => s" -p ${dbport} "
        case Mysql => s" -P ${dbport} "
        case Impala => s""
        case Hive => s""
      }
    }
    val dbhostStr = dbhost match {
      case null => ""
      case _ => dbtype match {
        case Psql => s" -h ${dbhost} "
        case Mysql => s" -h ${dbhost} "
          // impala-shell uses different port from jdbc
        case Impala => s" -i ${dbhost}:21000 "
        case Hive => s""
      }
    }
    val dbJdbcStr = dbtype match {
      case Hive => s" -u jdbc:hive2://${dbhost}:${dbport}/${dbname} "
      case _ => ""
    }

    // Return a concatinated options string
    dbJdbcStr + dbnameStr + dbuserStr + dbportStr + dbhostStr
  }
  
  /** 
   *  Execute a file as a bash script.
   *  
   *  @throws RuntimeException if failed
   */
  def executeCmd(cmd: String) : Unit = {
    // Make the file executable, if necessary
    val file = new java.io.File(cmd)
    if (file.isFile) file.setExecutable(true, false)
    log.info(s"""Executing command: "$cmd" """)
    val exitValue = cmd!(ProcessLogger(out => log.info(out)))
    // Depending on the exit value we return success or throw an exception
    exitValue match {
      case 0 => 
      case _ => throw new RuntimeException(s"Failure when executing script: ${cmd}")
    }
  }

  /**
   * Executes a SQL query by piping it into a file without talking to JDBC.
   * 
   * @param pipeOutFilePath   set if you need to pipe the query output to somewhere.
   * 
   * @throws IOException when bad I/O
   * @throws RuntimeException when script fails
   */
  def executeSqlQueriesByFile(dbSettings: DbSettings, query: String, 
      pipeOutFilePath: String = null) {
    val file = File.createTempFile(s"exec_sql", ".sh")
    val writer = new PrintWriter(file)

    val pipeOutStr = pipeOutFilePath match {
      case null => ""
      case _ => " > " + pipeOutFilePath
    }
    // Use single-quote in bash for reliability. Escape all ' into '\'' inside query.
    val cmd = Helpers.buildSqlCmd(dbSettings, query) + " " + pipeOutStr
    log.debug(s"Executing queries by file: ${cmd}")
    writer.println(s"${cmd}")
    writer.close()
    try {
      executeCmd(file.getAbsolutePath())
    } finally {
      // Delete the tmp file when finished
      file.delete();
    }
    
  }

  
  /** 
   *  Build a SQL command like 
   *    psql -c 'QUERY'
   *  or
   *    mysql --silent -N -e 'QUERY'
   *    
   *  Use single-quote in bash for reliability. Escape all ' into '\'' inside query.
   *  
   *  NOTE: Cannot be used with xargs where there are more quotes outside
   */
  def buildSqlCmd(dbSettings: DbSettings, query: String) : String = {

    // Branch by database driver type
    val dbtype = getDbType(dbSettings)
    
    val sqlQueryPrefix = dbtype match {
      case Psql => "psql " + getOptionString(dbSettings)
      case Mysql => "mysql " + getOptionString(dbSettings)
      case Impala => "impala-shell " + getOptionString(dbSettings)
      case Hive => "beeline " + getOptionString(dbSettings)
    }

    // Return the command below to handle " in queries
    dbtype match {
      case Psql => sqlQueryPrefix + 
        s""" -c '${query.replaceAll("'", "'\\\\''")}' """
      case Mysql => sqlQueryPrefix +
        s""" --silent -N -e '${query.replaceAll("'", "'\\\\''")}' """
      case Impala => sqlQueryPrefix +
        s""" --quiet -B -q '${query.replaceAll("'", "'\\\\''")}' """
      case Hive => sqlQueryPrefix +
        s""" --silent=true -e '${query.replaceAll("'", "'\\\\''")}' """
    }
  }
}
