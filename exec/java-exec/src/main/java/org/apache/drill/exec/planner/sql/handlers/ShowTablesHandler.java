/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.drill.exec.planner.sql.handlers;

import static org.apache.drill.exec.planner.sql.parser.DrillParserUtil.CHARSET;

import java.util.List;

import net.hydromatic.optiq.SchemaPlus;
import net.hydromatic.optiq.tools.Planner;
import net.hydromatic.optiq.tools.RelConversionException;

import org.apache.drill.exec.ops.QueryContext;
import org.apache.drill.exec.planner.sql.parser.DrillParserUtil;
import org.apache.drill.exec.planner.sql.parser.SqlShowTables;
import org.apache.drill.exec.store.AbstractSchema;
import org.eigenbase.sql.SqlIdentifier;
import org.eigenbase.sql.SqlLiteral;
import org.eigenbase.sql.SqlNode;
import org.eigenbase.sql.SqlNodeList;
import org.eigenbase.sql.SqlSelect;
import org.eigenbase.sql.fun.SqlStdOperatorTable;
import org.eigenbase.sql.parser.SqlParserPos;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

public class ShowTablesHandler extends DefaultSqlHandler {

  public ShowTablesHandler(Planner planner, QueryContext context) { super(planner, context); }

  /** Rewrite the parse tree as SELECT ... FROM INFORMATION_SCHEMA.`TABLES` ... */
  @Override
  public SqlNode rewrite(SqlNode sqlNode) throws RelConversionException{
    SqlShowTables node = unwrap(sqlNode, SqlShowTables.class);
    List<SqlNode> selectList = Lists.newArrayList();
    SqlNode fromClause;
    SqlNode where;

    // create select columns
    selectList.add(new SqlIdentifier("TABLE_SCHEMA", SqlParserPos.ZERO));
    selectList.add(new SqlIdentifier("TABLE_NAME", SqlParserPos.ZERO));

    fromClause = new SqlIdentifier(ImmutableList.of("INFORMATION_SCHEMA", "TABLES"), SqlParserPos.ZERO);

    final SqlIdentifier db = node.getDb();
    String tableSchema;
    if (db != null) {
      tableSchema = db.toString();
    } else {
      // If no schema is given in SHOW TABLES command, list tables from current schema
      SchemaPlus schema = context.getNewDefaultSchema();

      if (isRootSchema(schema)) {
        // If the default schema is a root schema, throw an error to select a default schema
        throw new RelConversionException("No schema selected. Select a schema using 'USE schema' command");
      }

      AbstractSchema drillSchema;

      try {
        drillSchema = getDrillSchema(schema);
      } catch(Exception ex) {
        throw new RelConversionException("Error while rewriting SHOW TABLES query: " + ex.getMessage(), ex);
      }

      tableSchema = drillSchema.getFullSchemaName();
    }

    where = DrillParserUtil.createCondition(
        new SqlIdentifier("TABLE_SCHEMA", SqlParserPos.ZERO),
        SqlStdOperatorTable.EQUALS,
        SqlLiteral.createCharString(tableSchema, CHARSET, SqlParserPos.ZERO));

    SqlNode filter = null;
    final SqlNode likePattern = node.getLikePattern();
    if (likePattern != null) {
      filter = DrillParserUtil.createCondition(
          new SqlIdentifier("TABLE_NAME", SqlParserPos.ZERO),
          SqlStdOperatorTable.LIKE,
          likePattern);
    } else if (node.getWhereClause() != null) {
      filter = node.getWhereClause();
    }

    where = DrillParserUtil.createCondition(where, SqlStdOperatorTable.AND, filter);

    return new SqlSelect(SqlParserPos.ZERO, null, new SqlNodeList(selectList, SqlParserPos.ZERO),
        fromClause, where, null, null, null, null, null, null);
  }
}

