/*
 * SqlCompletionManager.java
 *
 * Copyright (C) 2009-18 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.views.console.shell.assist;

import java.util.Map;

import org.rstudio.core.client.regex.Match;
import org.rstudio.core.client.regex.Pattern;
import org.rstudio.studio.client.common.codetools.CodeToolsServerOperations;
import org.rstudio.studio.client.workbench.views.source.editors.text.CompletionContext;
import org.rstudio.studio.client.workbench.views.source.editors.text.DocDisplay;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Token;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.TokenIterator;
import org.rstudio.studio.client.workbench.views.source.editors.text.assist.RChunkHeaderParser;

import com.google.gwt.core.client.JsArray;

public class SqlCompletionManager extends CompletionManagerBase
                                  implements CompletionManager
{
   public SqlCompletionManager(DocDisplay docDisplay,
                               CompletionPopupDisplay popup,
                               CodeToolsServerOperations server,
                               CompletionContext context)
   {
      super(popup, docDisplay, context);
      
      docDisplay_ = docDisplay;
      server_ = server;
      context_ = context;
   }
   
   @Override
   public void goToHelp()
   {
   }
   
   @Override
   public void goToDefinition()
   {
   }
   
   @Override
   public void getCompletions(String line,
                              CompletionRequestContext context)
   {
      String connection = discoverAssociatedConnectionString();
      server_.sqlGetCompletions(line, connection, completionContext(), context);
   }
   
   @Override
   protected boolean isTriggerCharacter(char ch)
   {
      return ch == '.';
   }
   
   private SqlCompletionParseContext completionContext()
   {
      SqlCompletionParseContext ctx = new SqlCompletionParseContext();
      
      int lowercaseKeywordCount = 0;
      int uppercaseKeywordCount = 0;
      
      // TODO: The framework of 'for each token in chunk' would be
      // worth off-loading somewhere re-usable
      TokenIterator it = fromChunkStart();
      for (Token token = it.getCurrentToken();
           token != null;
           token = it.stepForward())
      {
         if (token.hasType("keyword"))
         {
            String value = token.getValue();
            if (value.contentEquals(value.toLowerCase()))
               lowercaseKeywordCount += 1;
            else if (value.contentEquals(value.toUpperCase()))
               uppercaseKeywordCount += 1;
         }
         
         if (parseSqlFrom(it, ctx))
            continue;
         
         if (parseSqlInto(it, ctx))
            continue;
         
         if (parseSqlJoin(it, ctx))
            continue;
         
         if (parseSqlUpdate(it, ctx))
            continue;
         
         if (parseSqlIdentifier(it, ctx))
            continue;
         
         if (token.hasType("support.function.codeend"))
            break;
      }
      
      ctx.preferLowercaseKeywords = (lowercaseKeywordCount >= uppercaseKeywordCount);
      ctx.contextKeyword = findSqlContextKeyword();
      
      return ctx;
   }
   
   private String findSqlContextKeyword()
   {
      TokenIterator it = docDisplay_.createTokenIterator(docDisplay_.getCursorPosition());
      for (Token token = it.getCurrentToken();
           token != null;
           token = it.stepBackward())
      {
         if (token.hasType("support.function.codebegin"))
            break;
         
         if (it.bwdToMatchingToken())
            continue;
         
         if (it.getCurrentToken().typeEquals("keyword"))
            return it.getCurrentToken().getValue().toLowerCase();
      }
      
      return "";
   }
   
   private boolean parseSqlFrom(TokenIterator it, SqlCompletionParseContext ctx)
   {
      // consume initial from
      if (!consumeKeyword(it, "from"))
         return false;
      
      TokenIterator clone = it.clone();
      while (true)
      {
         // check for identifier
         if (!clone.getCurrentToken().hasType("identifier"))
            break;
         
         // check for text of form 'schema.table' and move on to
         // table name in that case
         String schema = "";
         if (clone.peekFwd().valueEquals("."))
         {
            schema = clone.getCurrentToken().getValue();
            
            if (!clone.moveToNextSignificantToken())
               break;
            
            if (!clone.valueEquals("."))
               break;
            
            if (!clone.moveToNextSignificantToken())
               break;
            
            if (!clone.getCurrentToken().hasType("identifier"))
               break;
         }
         
         // add schema + table name
         String table = clone.getCurrentToken().getValue();
         ctx.schemas.push(schema);
         ctx.tables.push(table);
         
         if (!clone.moveToNextSignificantToken())
            break;
         
         // consume optional 'as'
         consumeKeyword(clone, "as");
         
         // check for identifier -- if we have one, it's
         // the alias name
         if (!clone.getCurrentToken().hasType("identifier"))
            break;
         
         String alias = clone.getCurrentToken().getValue();
         ctx.aliases.set(alias, table);
         
         if (!clone.moveToNextSignificantToken())
            break;
         
         // continue parsing if we find a comma
         if (clone.getCurrentToken().valueMatches("\\s*,\\s*"))
         {
            if (!clone.moveToNextSignificantToken())
               break;
            
            continue;
         }
         
         break;
      }
      
      return true;
   }
   
   private boolean parseSqlInto(TokenIterator it, SqlCompletionParseContext ctx)
   {
      return parseSqlTableScopedKeyword("into", it, ctx);
   }
   
   private boolean parseSqlJoin(TokenIterator it, SqlCompletionParseContext ctx)
   {
      return parseSqlTableScopedKeyword("join", it, ctx);
   }
   
   private boolean parseSqlUpdate(TokenIterator it, SqlCompletionParseContext ctx)
   {
      return parseSqlTableScopedKeyword("update", it, ctx);
   }
   
   private boolean parseSqlIdentifier(TokenIterator it, SqlCompletionParseContext ctx)
   {
      Token token = it.getCurrentToken();
      if (!token.hasType("identifier"))
         return false;
      
      String value = token.getValue();
      ctx.identifiers.push(value);
      return true;
   }
   
   private boolean parseSqlTableScopedKeyword(String keyword,
                                              TokenIterator it,
                                              SqlCompletionParseContext ctx)
   {
      if (!consumeKeyword(it, keyword))
         return false;
      
      Token token = it.getCurrentToken();
      if (!token.hasType("identifier"))
         return false;
      
      String table = token.getValue();
      ctx.tables.push(table);
      return true;
   }
   
   private boolean consumeKeyword(TokenIterator it, String expectedValue)
   {
      Token token = it.getCurrentToken();
      if (!token.hasType("keyword"))
         return false;
      
      String tokenValue = token.getValue();
      if (!expectedValue.equalsIgnoreCase(tokenValue))
         return false;
      
      return it.moveToNextSignificantToken();
   }
   
   private String discoverAssociatedConnectionString()
   {
      if (docDisplay_.getFileType().isSql())
      {
         String firstLine = docDisplay_.getLine(0);
         Match match = RE_SQL_PREVIEW.match(firstLine, 0);
         if (match == null)
            return null;
         
         return match.getGroup(1);
      }
      else
      {
         int currentRow = docDisplay_.getCursorPosition().getRow();
         
         int row = currentRow - 1;
         for (; row >= 0; row--)
         {
            JsArray<Token> tokens = docDisplay_.getTokens(row);
            if (tokens.length() == 0)
               continue;
            
            Token token = tokens.get(0);
            if (token.hasType("support.function.codebegin"))
               break;
         }
         
         String chunkHeader = docDisplay_.getLine(row);
         
         Map<String, String> chunkOptions = RChunkHeaderParser.parse(chunkHeader);
         if (!chunkOptions.containsKey("connection"))
            return null;
         
         return chunkOptions.get("connection");
      }
   }
   
   private TokenIterator fromChunkStart()
   {
      TokenIterator it = docDisplay_.createTokenIterator();
      if (docDisplay_.getFileType().isSql())
         return it;
      
      it.moveToPosition(docDisplay_.getCursorPosition());
      it.findTokenTypeBwd("support.function.codebegin", false);
      return it;
   }
   
   private static final Pattern RE_SQL_PREVIEW = Pattern.create("^-{2,}\\s*!preview\\s+conn\\s*=\\s*(.*)$", "");
   
   private final DocDisplay docDisplay_;
   private final CodeToolsServerOperations server_;
   private final CompletionContext context_;
}
