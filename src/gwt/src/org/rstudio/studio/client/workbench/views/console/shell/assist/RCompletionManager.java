/*
 * RCompletionManager.java
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
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

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.*;
import com.google.gwt.event.logical.shared.SelectionEvent;
import com.google.gwt.event.logical.shared.SelectionHandler;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Event.NativePreviewEvent;
import com.google.gwt.user.client.Event.NativePreviewHandler;
import com.google.inject.Inject;

import org.rstudio.core.client.Debug;
import org.rstudio.core.client.Invalidation;
import org.rstudio.core.client.Rectangle;
import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.command.KeyboardShortcut;
import org.rstudio.core.client.events.SelectionCommitEvent;
import org.rstudio.core.client.events.SelectionCommitHandler;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.common.GlobalDisplay;
import org.rstudio.studio.client.common.GlobalProgressDelayer;
import org.rstudio.studio.client.common.SimpleRequestCallback;
import org.rstudio.studio.client.common.codetools.CodeToolsServerOperations;
import org.rstudio.studio.client.common.codetools.RCompletionType;
import org.rstudio.studio.client.common.filetypes.FileTypeRegistry;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.server.Void;
import org.rstudio.studio.client.workbench.codesearch.model.FunctionDefinition;
import org.rstudio.studio.client.workbench.prefs.model.UIPrefs;
import org.rstudio.studio.client.workbench.views.console.shell.assist.CompletionRequester.CompletionResult;
import org.rstudio.studio.client.workbench.views.console.shell.assist.CompletionRequester.QualifiedName;
import org.rstudio.studio.client.workbench.views.console.shell.editor.InputEditorDisplay;
import org.rstudio.studio.client.workbench.views.console.shell.editor.InputEditorLineWithCursorPosition;
import org.rstudio.studio.client.workbench.views.console.shell.editor.InputEditorPosition;
import org.rstudio.studio.client.workbench.views.console.shell.editor.InputEditorSelection;
import org.rstudio.studio.client.workbench.views.console.shell.editor.InputEditorUtil;
import org.rstudio.studio.client.workbench.views.source.editors.text.AceEditor;
import org.rstudio.studio.client.workbench.views.source.editors.text.DocDisplay;
import org.rstudio.studio.client.workbench.views.source.editors.text.NavigableSourceEditor;
import org.rstudio.studio.client.workbench.views.source.editors.text.RCompletionContext;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.CodeModel;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.DplyrJoinContext;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Position;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.RInfixData;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Range;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.TokenCursor;
import org.rstudio.studio.client.workbench.views.source.events.CodeBrowserNavigationEvent;
import org.rstudio.studio.client.workbench.views.source.model.RnwCompletionContext;
import org.rstudio.studio.client.workbench.views.source.model.SourcePosition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public class RCompletionManager implements CompletionManager
{  
   // globally suppress F1 and F2 so no default browser behavior takes those
   // keystrokes (e.g. Help in Chrome)
   static
   {
      Event.addNativePreviewHandler(new NativePreviewHandler() {
         @Override
         public void onPreviewNativeEvent(NativePreviewEvent event)
         {
            if (event.getTypeInt() == Event.ONKEYDOWN)
            {
               int keyCode = event.getNativeEvent().getKeyCode();
               if ((keyCode == 112 || keyCode == 113) &&
                   KeyboardShortcut.NONE ==
                      KeyboardShortcut.getModifierValue(event.getNativeEvent()))
               {
                 event.getNativeEvent().preventDefault();
               }
            }
         }
      });   
   }
   
   public RCompletionManager(InputEditorDisplay input,
                             NavigableSourceEditor navigableSourceEditor,
                             CompletionPopupDisplay popup,
                             CodeToolsServerOperations server,
                             InitCompletionFilter initFilter,
                             RCompletionContext rContext,
                             RnwCompletionContext rnwContext,
                             DocDisplay docDisplay,
                             boolean canAutoPopup)
   {
      RStudioGinjector.INSTANCE.injectMembers(this);
      
      input_ = input ;
      navigableSourceEditor_ = navigableSourceEditor;
      popup_ = popup ;
      server_ = server ;
      requester_ = new CompletionRequester(server_, rnwContext, navigableSourceEditor);
      initFilter_ = initFilter ;
      rContext_ = rContext;
      rnwContext_ = rnwContext;
      docDisplay_ = docDisplay;
      canAutoPopup_ = canAutoPopup;
      
      input_.addBlurHandler(new BlurHandler() {
         public void onBlur(BlurEvent event)
         {
            if (!ignoreNextInputBlur_)
               invalidatePendingRequests() ;
            ignoreNextInputBlur_ = false ;
         }
      }) ;

      input_.addClickHandler(new ClickHandler()
      {
         public void onClick(ClickEvent event)
         {
            invalidatePendingRequests();
         }
      });

      popup_.addSelectionCommitHandler(new SelectionCommitHandler<QualifiedName>() {
         public void onSelectionCommit(SelectionCommitEvent<QualifiedName> event)
         {
            assert context_ != null : "onSelection called but handler is null" ;
            if (context_ != null)
               context_.onSelection(event.getSelectedItem()) ;
         }
      }) ;
      
      popup_.addSelectionHandler(new SelectionHandler<QualifiedName>() {
         public void onSelection(SelectionEvent<QualifiedName> event)
         {
            context_.showHelp(event.getSelectedItem()) ;
         }
      }) ;
      
      popup_.addMouseDownHandler(new MouseDownHandler() {
         public void onMouseDown(MouseDownEvent event)
         {
            ignoreNextInputBlur_ = true ;
         }
      }) ;
   }
   
   @Inject
   public void initialize(GlobalDisplay globalDisplay,
                          FileTypeRegistry fileTypeRegistry,
                          EventBus eventBus,
                          HelpStrategy helpStrategy,
                          UIPrefs uiPrefs)
   {
      globalDisplay_ = globalDisplay;
      fileTypeRegistry_ = fileTypeRegistry;
      eventBus_ = eventBus;
      helpStrategy_ = helpStrategy;
      uiPrefs_ = uiPrefs;
   }

   public void close()
   {
      popup_.hide();
   }
   
   public void codeCompletion()
   {
      if (initFilter_ == null || initFilter_.shouldComplete(null))
         beginSuggest(true, false, true);
   }
   
   public void goToHelp()
   {
      InputEditorLineWithCursorPosition linePos = 
            InputEditorUtil.getLineWithCursorPosition(input_);

      server_.getHelpAtCursor(
            linePos.getLine(), linePos.getPosition(),
            new SimpleRequestCallback<Void>("Help"));
   }
   
   public void goToFunctionDefinition()
   {   
      // determine current line and cursor position
      InputEditorLineWithCursorPosition lineWithPos = 
                      InputEditorUtil.getLineWithCursorPosition(input_);
      
      // lookup function definition at this location
      
      // delayed progress indicator
      final GlobalProgressDelayer progress = new GlobalProgressDelayer(
            globalDisplay_, 1000, "Searching for function definition...");
      
      server_.getFunctionDefinition(
         lineWithPos.getLine(),
         lineWithPos.getPosition(), 
         new ServerRequestCallback<FunctionDefinition>() {
            @Override
            public void onResponseReceived(FunctionDefinition def)
            {
                // dismiss progress
                progress.dismiss();
                    
                // if we got a hit
                if (def.getFunctionName() != null)
                {   
                   // search locally if a function navigator was provided
                   if (navigableSourceEditor_ != null)
                   {
                      // try to search for the function locally
                      SourcePosition position = 
                         navigableSourceEditor_.findFunctionPositionFromCursor(
                                                         def.getFunctionName());
                      if (position != null)
                      {
                         navigableSourceEditor_.navigateToPosition(position, 
                                                                   true);
                         return; // we're done
                      }

                   }
                   
                   // if we didn't satisfy the request using a function
                   // navigator and we got a file back from the server then
                   // navigate to the file/loc
                   if (def.getFile() != null)
                   {  
                      fileTypeRegistry_.editFile(def.getFile(), 
                                                 def.getPosition());
                   }
                   
                   // if we didn't get a file back see if we got a 
                   // search path definition
                   else if (def.getSearchPathFunctionDefinition() != null)
                   {
                      eventBus_.fireEvent(new CodeBrowserNavigationEvent(
                                     def.getSearchPathFunctionDefinition()));
                      
                   }
               }
            }

            @Override
            public void onError(ServerError error)
            {
               progress.dismiss();
               
               globalDisplay_.showErrorMessage("Error Searching for Function",
                                               error.getUserMessage());
            }
         });
   }
   
   
   public boolean previewKeyDown(NativeEvent event)
   {
      /**
       * KEYS THAT MATTER
       *
       * When popup not showing:
       * Tab - attempt completion (handled in Console.java)
       * 
       * When popup showing:
       * Esc - dismiss popup
       * Enter/Tab/Right-arrow - accept current selection
       * Up-arrow/Down-arrow - change selected item
       * Left-arrow - dismiss popup
       * [identifier] - narrow suggestions--or if we're lame, just dismiss
       * All others - dismiss popup
       */
      
      nativeEvent_ = event;

      int keycode = event.getKeyCode();
      int modifier = KeyboardShortcut.getModifierValue(event);

      if (!popup_.isShowing())
      {
         if (CompletionUtils.isCompletionRequest(event, modifier))
         {
            if (initFilter_ == null || initFilter_.shouldComplete(event))
            {
               return beginSuggest(true, false, true);
            }
         }
         else if (keycode == 112 // F1
                  && modifier == KeyboardShortcut.NONE)
         {
            goToHelp();
         }
         else if (keycode == 113 // F2
                  && modifier == KeyboardShortcut.NONE)
         {
            goToFunctionDefinition();
         }
      }
      else
      {
         switch (keycode)
         {
         // chrome on ubuntu now sends this before every keydown
         // so we need to explicitly ignore it. see:
         // https://github.com/ivaynberg/select2/issues/2482
         case KeyCodes.KEY_WIN_IME: 
            return false ;
            
         case KeyCodes.KEY_SHIFT:
         case KeyCodes.KEY_CTRL:
         case KeyCodes.KEY_ALT:
         case KeyCodes.KEY_MAC_FF_META:
         case KeyCodes.KEY_WIN_KEY_LEFT_META:
            return false ; // bare modifiers should do nothing
         }
         
         if (modifier == KeyboardShortcut.NONE)
         {
            if (keycode == KeyCodes.KEY_ESCAPE)
            {
               invalidatePendingRequests() ;
               return true ;
            }
            else if (keycode == KeyCodes.KEY_TAB
                  || keycode == KeyCodes.KEY_ENTER
                  || keycode == KeyCodes.KEY_RIGHT)
            {
               QualifiedName value = popup_.getSelectedValue() ;
               if (value != null)
               {
                  context_.onSelection(value) ;
                  return true ;
               }
            }
            else if (keycode == KeyCodes.KEY_UP)
               return popup_.selectPrev() ;
            else if (keycode == KeyCodes.KEY_DOWN)
               return popup_.selectNext() ;
            else if (keycode == KeyCodes.KEY_PAGEUP)
               return popup_.selectPrevPage() ;
            else if (keycode == KeyCodes.KEY_PAGEDOWN)
               return popup_.selectNextPage() ;
            else if (keycode == KeyCodes.KEY_HOME)
               return popup_.selectFirst() ;
            else if (keycode == KeyCodes.KEY_END)
               return popup_.selectLast() ;
            else if (keycode == KeyCodes.KEY_LEFT)
            {
               invalidatePendingRequests() ;
               return true ;
            }
            else if (keycode == 112) // F1
            {
               context_.showHelpTopic() ;
               return true ;
            }
            else if (keycode == 113) // F2
            {
               goToFunctionDefinition();
               return true;
            }
         }
         
         if (canContinueCompletions(event))
            return false;
         
         // if we insert a '/', we're probably forming a directory --
         // pop up completions
         if (keycode == 191 && modifier == KeyboardShortcut.NONE)
         {
            input_.insertCode("/");
            return beginSuggest(true, true, false);
         }
         
         // continue showing completions on backspace
         if (keycode == KeyCodes.KEY_BACKSPACE && modifier == KeyboardShortcut.NONE)
         {
            int cursorColumn = input_.getCursorPosition().getColumn();
            String currentLine = docDisplay_.getCurrentLine();
            
            // only suggest if the character previous to the cursor is an R identifier
            // also halt suggestions if we're about to remove the only character on the line
            if (cursorColumn > 0)
            {
               char ch = currentLine.charAt(cursorColumn - 2);
               char prevCh = currentLine.charAt(cursorColumn - 3);
               
               boolean isAcceptableCharSequence = isValidForRIdentifier(ch) ||
                     (ch == ':' && prevCh == ':') ||
                     ch == '$' ||
                     ch == '@';
               
               if (currentLine.length() > 0 &&
                     cursorColumn > 0 &&
                     isAcceptableCharSequence)
               {
                  // manually remove the previous character
                  InputEditorSelection selection = input_.getSelection();
                  InputEditorPosition start = selection.getStart().movePosition(-1, true);
                  InputEditorPosition end = selection.getStart();

                  if (currentLine.charAt(cursorColumn) == ')' && currentLine.charAt(cursorColumn - 1) == '(')
                     end = selection.getStart().movePosition(1, true);

                  input_.setSelection(new InputEditorSelection(start, end));
                  input_.replaceSelection("", false);
                  
                  return beginSuggest(false, false, false);
               }
            }
            else
            {
               invalidatePendingRequests();
               return true;
            }
         }
         
         invalidatePendingRequests();
         return false ;
      }
      
      return false ;
   }
   
   private boolean isValidForRIdentifier(char c) {
      return (c >= 'a' && c <= 'z') ||
             (c >= 'A' && c <= 'Z') ||
             (c == '.') ||
             (c == '_');
   }
   
   private boolean checkCanAutoPopup(char c, int lookbackLimit)
   {
      String currentLine = docDisplay_.getCurrentLine();
      Position cursorPos = input_.getCursorPosition();
      int cursorColumn = cursorPos.getColumn();

      boolean canAutocomplete = canAutoPopup_ && 
            (currentLine.length() > lookbackLimit - 1 && isValidForRIdentifier(c));

      if (canAutocomplete)
      {
         for (int i = 0; i < lookbackLimit; i++)
         {
            if (!isValidForRIdentifier(currentLine.charAt(cursorColumn - i - 1)))
            {
               canAutocomplete = false;
               break;
            }
         }
      }

      return canAutocomplete;
      
   }
   
   public boolean previewKeyPress(char c)
   {
      if (popup_.isShowing())
      {
         if (isValidForRIdentifier(c) || c == ':')
         {
            Scheduler.get().scheduleDeferred(new ScheduledCommand()
            {
               @Override
               public void execute()
               {
                  beginSuggest(false, true, false);
               }
            });
         }
      }
      else
      {
         
         // Perform an auto-popup if a set number of R identifier characters
         // have been inserted
         final boolean canAutoPopup = checkCanAutoPopup(c, 4);
         char prevChar = docDisplay_.getCurrentLine().charAt(
               input_.getCursorPosition().getColumn() - 1); 
         
         if (
               (canAutoPopup) ||
               (c == ':' && prevChar == ':') ||
               (c == '$') ||
               (c == '@') ||
               isSweaveCompletion(c))
         {
            Scheduler.get().scheduleDeferred(new ScheduledCommand()
            {
               @Override
               public void execute()
               {
                  beginSuggest(true, true, false);
               }
            });
         }
         else if (CompletionUtils.handleEncloseSelection(input_, c))
         {
            return true;
         }
      }
      return false ;
   }
   
   @SuppressWarnings("unused")
   private boolean isRoxygenTagValidHere()
   {
      if (input_.getText().matches("\\s*#+'.*"))
      {
         String linePart = input_.getText().substring(0, input_.getSelection().getStart().getPosition());
         if (linePart.matches("\\s*#+'\\s*"))
            return true;
      }
      return false;
   }

   private boolean isSweaveCompletion(char c)
   {
      if (rnwContext_ == null || (c != ',' && c != ' ' && c != '='))
         return false;

      int optionsStart = rnwContext_.getRnwOptionsStart(
            input_.getText(),
            input_.getSelection().getStart().getPosition());

      if (optionsStart < 0)
      {
         return false;
      }

      String linePart = input_.getText().substring(
            optionsStart,
            input_.getSelection().getStart().getPosition());

      return c != ' ' || linePart.matches(".*,\\s*");
   }

   private static boolean canContinueCompletions(NativeEvent event)
   {
      if (event.getAltKey()
            || event.getCtrlKey()
            || event.getMetaKey())
      {
         return false ;
      }
      
      int keyCode = event.getKeyCode() ;
      if (keyCode >= 'a' && keyCode <= 'z')
         return true ;
      if (keyCode >= 'A' && keyCode <= 'Z')
         return true ;
      if (keyCode == ' ')
         return true ;
      if (keyCode == 189) // dash
         return true ;
      if (keyCode == 189 && event.getShiftKey()) // underscore
         return true ;
      
      if (event.getShiftKey())
         return false ;
      
      if (keyCode >= '0' && keyCode <= '9')
         return true ;
      if (keyCode == 190) // period
         return true ;
      
      return false ;
   }

   private void invalidatePendingRequests()
   {
      invalidatePendingRequests(true) ;
   }

   private void invalidatePendingRequests(boolean flushCache)
   {
      invalidation_.invalidate();
      if (popup_.isShowing())
         popup_.hide() ;
      if (flushCache)
         requester_.flushCache() ;
   }
   
   
   // Things we need to form an appropriate autocompletion:
   //
   // 1. The token to the left of the cursor,
   // 2. The associated function call (if any -- for arguments),
   // 3. The associated data for a `[` call (if any -- completions from data object),
   // 4. The associated data for a `[[` call (if any -- completions from data object)
   class AutocompletionContext {
      
      // Be sure to sync these with 'SessionCodeTools.R'!
      public static final int TYPE_UNKNOWN = 0;
      public static final int TYPE_FUNCTION = 1;
      public static final int TYPE_SINGLE_BRACKET = 2;
      public static final int TYPE_DOUBLE_BRACKET = 3;
      public static final int TYPE_NAMESPACE_EXPORTED = 4;
      public static final int TYPE_NAMESPACE_ALL = 5;
      public static final int TYPE_DOLLAR = 6;
      public static final int TYPE_AT = 7;
      public static final int TYPE_FILE = 8;
      public static final int TYPE_CHUNK = 9;
      public static final int TYPE_ROXYGEN = 10;
      public static final int TYPE_HELP = 11;
      
      public AutocompletionContext(
            String token,
            List<String> assocData,
            List<Integer> dataType,
            List<Integer> numCommas,
            String functionCallString)
      {
         token_ = token;
         assocData_ = assocData;
         dataType_ = dataType;
         numCommas_ = numCommas;
         functionCallString_ = functionCallString;
      }
      
      public AutocompletionContext(
            String token,
            ArrayList<String> assocData,
            ArrayList<Integer> dataType)
      {
         token_ = token;
         assocData_ = assocData;
         dataType_ = dataType;
         numCommas_ = Arrays.asList(0);
         functionCallString_ = "";
      }
      
      public AutocompletionContext(
            String token,
            String assocData,
            int dataType)
      {
         token_ = token;
         assocData_ = Arrays.asList(assocData);
         dataType_ = Arrays.asList(dataType);
         numCommas_ = Arrays.asList(0);
         functionCallString_ = "";
      }
      
      
      public AutocompletionContext(
            String token,
            int dataType)
      {
         token_ = token;
         assocData_ = Arrays.asList("");
         dataType_ = Arrays.asList(dataType);
         numCommas_ = Arrays.asList(0);
         functionCallString_ = "";
      }
      
      public AutocompletionContext()
      {
         token_ = "";
         assocData_ = new ArrayList<String>();
         dataType_ = new ArrayList<Integer>();
         numCommas_ = new ArrayList<Integer>();
         functionCallString_ = "";
      }

      public String getToken()
      {
         return token_;
      }

      public void setToken(String token)
      {
         this.token_ = token;
      }

      public List<String> getAssocData()
      {
         return assocData_;
      }

      public void setAssocData(List<String> assocData)
      {
         this.assocData_ = assocData;
      }

      public List<Integer> getDataType()
      {
         return dataType_;
      }

      public void setDataType(List<Integer> dataType)
      {
         this.dataType_ = dataType;
      }

      public List<Integer> getNumCommas()
      {
         return numCommas_;
      }

      public void setNumCommas(List<Integer> numCommas)
      {
         this.numCommas_ = numCommas;
      }

      public String getFunctionCallString()
      {
         return functionCallString_;
      }

      public void setFunctionCallString(String functionCallString)
      {
         this.functionCallString_ = functionCallString;
      }
      
      public void add(String assocData, Integer dataType, Integer numCommas)
      {
         assocData_.add(assocData);
         dataType_.add(dataType);
         numCommas_.add(numCommas);
      }
      
      public void add(String assocData, Integer dataType)
      {
         add(assocData, dataType, 0);
      }
      
      public void add(String assocData)
      {
         add(assocData, AutocompletionContext.TYPE_UNKNOWN, 0);
      }

      private String token_;
      private List<String> assocData_;
      private List<Integer> dataType_;
      private List<Integer> numCommas_;
      private String functionCallString_;
      
   }

   /**
    * If false, the suggest operation was aborted
    */
   private boolean beginSuggest(boolean flushCache, boolean implicit, boolean canAutoInsert)
   {
      if (!input_.isSelectionCollapsed())
         return false ;
      
      invalidatePendingRequests(flushCache);
      
      InputEditorSelection selection = input_.getSelection() ;
      if (selection == null)
         return false;
      
      int cursorCol = selection.getStart().getPosition();
      String firstLine = input_.getText().substring(0, cursorCol);
      
      // don't auto-complete at the start of comments
      if (firstLine.matches(".*#+\\s*$"))
      {
         return false;
      }
      
      // don't auto-insert if we're within a comment
      if (!StringUtil.stripRComment(firstLine).equals(firstLine))
      {
         canAutoInsert = false;
      }
      
      // don't auto-complete with tab on lines with only whitespace,
      // if the insertion character was a tab
      if (nativeEvent_ != null &&
            nativeEvent_.getKeyCode() == KeyCodes.KEY_TAB)
         if (firstLine.matches("^\\s*$"))
            return false;
      
      AutocompletionContext context = getAutocompletionContext();
      
      if (!input_.hasSelection())
      {
         Debug.log("Cursor wasn't in input box or was in subelement");
         return false ;
      }

      context_ = new CompletionRequestContext(invalidation_.getInvalidationToken(),
                                              selection,
                                              canAutoInsert);
      
      RInfixData infixData = RInfixData.create();
      AceEditor editor = (AceEditor) docDisplay_;
      if (editor != null)
      {
         CodeModel codeModel = editor.getSession().getMode().getCodeModel();
         TokenCursor cursor = codeModel.getTokenCursor();
         
         if (cursor.moveToPosition(input_.getCursorPosition()))
         {
            String token = "";
            if (cursor.currentType() == "identifier")
               token = cursor.currentValue();
            
            String cursorPos = "left";
            if (cursor.currentValue() == "=")
               cursorPos = "right";
            
            TokenCursor clone = cursor.cloneCursor();
            if (clone.moveToPreviousToken())
               if (clone.currentValue() == "=")
                  cursorPos = "right";
            
            // Try to get a dplyr join completion
            DplyrJoinContext joinContext =
                  codeModel.getDplyrJoinContextFromInfixChain(cursor);
            
            // If that failed, try a non-infix lookup
            if (joinContext == null)
            {
               String joinString =
                     getDplyrJoinString(editor, cursor);
               
               if (!StringUtil.isNullOrEmpty(joinString))
               {
                  requester_.getDplyrJoinCompletionsString(
                        token,
                        joinString,
                        cursorPos,
                        implicit,
                        context_);

                  return true;
               }
            }
            else
            {
               requester_.getDplyrJoinCompletions(
                     joinContext,
                     implicit,
                     context_);
               return true;
               
            }
            
            // Try to see if there's an object name we should use to supplement
            // completions
            if (cursor.moveToPosition(input_.getCursorPosition()))
               infixData = codeModel.getDataFromInfixChain(cursor);
         }
      }
      
      String filePath = getSourceDocumentPath();
      if (filePath == null)
         filePath = "";
      
      requester_.getCompletions(
            context.getToken(),
            context.getAssocData(),
            context.getDataType(),
            context.getNumCommas(),
            context.getFunctionCallString(),
            infixData.getDataName(),
            infixData.getAdditionalArgs(),
            infixData.getExcludeArgs(),
            infixData.getExcludeArgsFromObject(),
            filePath,
            implicit,
            context_);

      return true ;
   }
   
   private String getDplyrJoinString(
         AceEditor editor,
         TokenCursor cursor)
   {
      while (true)
      {
         int commaCount = cursor.findOpeningBracketCountCommas("(", true);
         if (commaCount == -1)
            break;
         
         if (!cursor.moveToPreviousToken())
            return "";

         if (!cursor.currentValue().matches(".*join$"))
            continue;
         
         if (commaCount < 2)
            return "";

         Position start = cursor.currentPosition();
         if (!cursor.moveToNextToken())
            return "";

         if (!cursor.fwdToMatchingToken())
            return "";

         Position end = cursor.currentPosition();
         end.setColumn(end.getColumn() + 1);

         return editor.getTextForRange(Range.fromPoints(
               start, end));
      }
      return "";
   }
   
   
   private AutocompletionContext getAutocompletionContextForFile(
         String line)
   {
      int index = Math.max(line.lastIndexOf('"'), line.lastIndexOf('\''));
      return new AutocompletionContext(
            line.substring(index + 1),
            AutocompletionContext.TYPE_FILE);
   }
   
   private void addAutocompletionContextForNamespace(
         String token,
         AutocompletionContext context)
   {
         String[] splat = token.split(":{2,3}");
         String left = "";
         
         if (splat.length <= 0)
         {
            left = "";
         }
         else
         {
            left = splat[0];
         }
         
         int type = token.contains(":::") ?
               AutocompletionContext.TYPE_NAMESPACE_ALL :
                  AutocompletionContext.TYPE_NAMESPACE_EXPORTED;
               
         context.add(left, type);
   }
   
   
   private boolean addAutocompletionContextForDollar(AutocompletionContext context)
   {
      // Establish an evaluation context by looking backwards
      AceEditor editor = (AceEditor) docDisplay_;
      if (editor == null)
         return false;
      
      CodeModel codeModel = editor.getSession().getMode().getCodeModel();
      codeModel.tokenizeUpToRow(input_.getCursorPosition().getRow());
      
      TokenCursor cursor = codeModel.getTokenCursor();
         
      if (!cursor.moveToPosition(input_.getCursorPosition()))
         return false;
      
      // Move back to the '$'
      while (cursor.currentValue() != "$" && cursor.currentValue() != "@")
         if (!cursor.moveToPreviousToken())
            return false;
      
      int type = cursor.currentValue() == "$" ?
            AutocompletionContext.TYPE_DOLLAR :
            AutocompletionContext.TYPE_AT;
      
      // Put a cursor here
      TokenCursor contextEndCursor = cursor.cloneCursor();
      
      // We allow for arbitrary elements previous, so we want to get e.g.
      //
      //     env::foo()$bar()[1]$baz
      // Get the string forming the context
      if (!cursor.findStartOfEvaluationContext())
         return false;
      
      String data = editor.getTextForRange(Range.fromPoints(
            cursor.currentPosition(),
            contextEndCursor.currentPosition()));
      
      context.add(data, type);
      return true;
   }
   
   
   private AutocompletionContext getAutocompletionContext()
   {
      AutocompletionContext context = new AutocompletionContext();
      
      String firstLine = input_.getText();
      int row = input_.getCursorPosition().getRow();
      
      // trim to cursor position
      firstLine = firstLine.substring(0, input_.getCursorPosition().getColumn());
      
      // Get the token at the cursor position
      String token = firstLine.replaceAll(".*[^a-zA-Z0-9._:$@-]", "");
      
      // If we're completing an object within a string, assume it's a
      // file-system completion
      String firstLineStripped = StringUtil.stripBalancedQuotes(
            StringUtil.stripRComment(firstLine));
      
      if (firstLineStripped.indexOf('\'') != -1 || 
          firstLineStripped.indexOf('"') != -1)
         return getAutocompletionContextForFile(firstLine);
      
      // If this line starts with '```{', then we're completing chunk options
      // pass the whole line as a token
      if (firstLine.startsWith("```{") || firstLine.startsWith("<<"))
         return new AutocompletionContext(firstLine, AutocompletionContext.TYPE_CHUNK);
      
      // If this line starts with a '?', assume it's a help query
      if (firstLine.matches("^\\s*[?].*"))
         return new AutocompletionContext(token, AutocompletionContext.TYPE_HELP);
      
      // escape early for roxygen
      if (firstLine.matches("\\s*#+'.*@"))
         return new AutocompletionContext(
               token, AutocompletionContext.TYPE_ROXYGEN);
      
      // if the line is currently within a comment, bail -- this ensures
      // that we can auto-complete within a comment line (but we only
      // need context from that line)
      if (!firstLine.equals(StringUtil.stripRComment(firstLine)))
         return new AutocompletionContext("", AutocompletionContext.TYPE_UNKNOWN);
      
      // If the token has '$' or '@', escape early as we'll be completing
      // either from names or an overloaded `$` method
      if (token.contains("$") || token.contains("@"))
         addAutocompletionContextForDollar(context);
      
      // If the token has '::' or ':::', escape early as we'll be completing
      // something from a namespace
      if (token.contains("::"))
         addAutocompletionContextForNamespace(token, context);
      
      // Now strip the '$' and '@' post-hoc since they're not really part
      // of the identifier
      token = token.replaceAll(".*[$@:]", "");
      context.setToken(token);
      
      // access to the R Code model
      AceEditor editor = (AceEditor) docDisplay_;
      if (editor == null)
         return context;
      
      CodeModel codeModel = editor.getSession().getMode().getCodeModel();
      
      // We might need to grab content from further up in the document than
      // the current cursor position -- so tokenize ahead.
      codeModel.tokenizeUpToRow(row + 100);
      
      // Make a token cursor and place it at the first token previous
      // to the cursor.
      TokenCursor tokenCursor = codeModel.getTokenCursor();
      if (!tokenCursor.moveToPosition(input_.getCursorPosition()))
         return context;
      
      TokenCursor startCursor = tokenCursor.cloneCursor();
      boolean startedOnEquals = tokenCursor.currentValue() == "=";
      if (startCursor.currentType() == "identifier")
         if (startCursor.moveToPreviousToken())
            if (startCursor.currentValue() == "=")
            {
               startedOnEquals = true;
               startCursor.moveToNextToken();
            }
      
      // Find an opening '(' or '[' -- this provides the function or object
      // for completion.
      int initialNumCommas = 0;
      if (tokenCursor.currentValue() != "(" && tokenCursor.currentValue() != "[")
      {
         int commaCount = tokenCursor.findOpeningBracketCountCommas(new String[]{ "[", "(" }, true);
         if (commaCount == -1)
         {
            commaCount = tokenCursor.findOpeningBracketCountCommas("[", false);
            if (commaCount == -1)
               return context;
            else
               initialNumCommas = commaCount;
         }
         else
         {
            initialNumCommas = commaCount;
         }
      }
      
      // Figure out whether we're looking at '(', '[', or '[[',
      // and place the token cursor on the first token preceding.
      TokenCursor endOfDecl = tokenCursor.cloneCursor();
      int initialDataType = AutocompletionContext.TYPE_UNKNOWN;
      if (tokenCursor.currentValue() == "(")
      {
         // Don't produce function argument completions
         // if the cursor is on, or after, an '='
         if (!startedOnEquals)
            initialDataType = AutocompletionContext.TYPE_FUNCTION;
         else
            initialDataType = AutocompletionContext.TYPE_UNKNOWN;
         
         if (!tokenCursor.moveToPreviousToken())
            return context;
      }
      else if (tokenCursor.currentValue() == "[")
      {
         if (!tokenCursor.moveToPreviousToken())
            return context;
         
         if (tokenCursor.currentValue() == "[")
         {
            if (!endOfDecl.moveToPreviousToken())
               return context;
            
            initialDataType = AutocompletionContext.TYPE_DOUBLE_BRACKET;
            if (!tokenCursor.moveToPreviousToken())
               return context;
         }
         else
         {
            initialDataType = AutocompletionContext.TYPE_SINGLE_BRACKET;
         }
      }
      
      // Get the string marking the function or data
      if (!tokenCursor.findStartOfEvaluationContext())
         return context;
      
      // Try to get the function call string -- either there's
      // an associated closing paren we can use, or we should just go up
      // to the current cursor position
      
      // default case: use start cursor
      Position endPos = startCursor.currentPosition();
      endPos.setColumn(endPos.getColumn() + startCursor.currentValue().length());
      
      // try to look forward for closing paren
      if (endOfDecl.currentValue() == "(")
      {
         TokenCursor closingParenCursor = endOfDecl.cloneCursor();
         if (closingParenCursor.fwdToMatchingToken())
         {
            endPos = closingParenCursor.currentPosition();
            endPos.setColumn(endPos.getColumn() + 1);
         }
      }
      
      // We can now set the function call string
      context.setFunctionCallString(
            editor.getTextForRange(Range.fromPoints(
                  tokenCursor.currentPosition(), endPos)));
      
      String initialData = 
            docDisplay_.getTextForRange(Range.fromPoints(
                  tokenCursor.currentPosition(),
                  endOfDecl.currentPosition()));
      
      // And the first context
      context.add(initialData, initialDataType, initialNumCommas);

      // Get the rest of the single-bracket contexts for completions as well
      String assocData;
      int dataType;
      int numCommas;
      while (true)
      {
         int commaCount = tokenCursor.findOpeningBracketCountCommas("[", false);
         if (commaCount == -1)
            break;
         
         numCommas = commaCount;
         
         TokenCursor declEnd = tokenCursor.cloneCursor();
         if (!tokenCursor.moveToPreviousToken())
            return context;
         
         if (tokenCursor.currentValue() == "[")
         {
            if (!declEnd.moveToPreviousToken())
               return context;
            
            dataType = AutocompletionContext.TYPE_DOUBLE_BRACKET;
            if (!tokenCursor.moveToPreviousToken())
               return context;
         }
         else
         {
            dataType = AutocompletionContext.TYPE_SINGLE_BRACKET;
         }
         
         assocData =
            docDisplay_.getTextForRange(Range.fromPoints(
                  tokenCursor.currentPosition(),
                  declEnd.currentPosition()));
         
         context.add(assocData, dataType, numCommas);
      }
      
      return context;
      
   }
   
   /**
    * It's important that we create a new instance of this each time.
    * It maintains state that is associated with a completion request.
    */
   private final class CompletionRequestContext extends
         ServerRequestCallback<CompletionResult>
   {
      public CompletionRequestContext(Invalidation.Token token,
                                      InputEditorSelection selection,
                                      boolean canAutoAccept)
      {
         invalidationToken_ = token ;
         selection_ = selection ;
         canAutoAccept_ = canAutoAccept;
      }
      
      public void showHelp(QualifiedName selectedItem)
      {
         helpStrategy_.showHelp(selectedItem, popup_);
      }
      
      public void showHelpTopic()
      {
         helpStrategy_.showHelpTopic(popup_.getSelectedValue());
      }

      @Override
      public void onError(ServerError error)
      {
         if (invalidationToken_.isInvalid())
            return ;
         
         RCompletionManager.this.popup_.showErrorMessage(
                  error.getUserMessage(), 
                  new PopupPositioner(input_.getCursorBounds(), popup_)) ;
      }

      @Override
      public void onResponseReceived(CompletionResult completions)
      {
         if (invalidationToken_.isInvalid())
            return ;
         
         final QualifiedName[] results
                     = completions.completions.toArray(new QualifiedName[0]) ;
         
         if (results.length == 0)
         {
            boolean lastInputWasTab =
                  (nativeEvent_ != null && nativeEvent_.getKeyCode() == KeyCodes.KEY_TAB);
            
            boolean lineIsWhitespace = docDisplay_.getCurrentLine().matches("^\\s*$");
            
            if (lastInputWasTab && lineIsWhitespace)
            {
               docDisplay_.insertCode("\t");
               return;
            }
            
            if (canAutoAccept_) {
               popup_.showErrorMessage(
                     "(No matches)", 
                     new PopupPositioner(input_.getCursorBounds(), popup_));
            }
            else
            {
               // Show an empty popup message offscreen -- this is a hack to
               // ensure that we can get completion results on backspace after a
               // failed completion, e.g. 'stats::rna' -> 'stats::rn'
               Rectangle offScreen = new Rectangle(-100, -100, 0, 0);
               popup_.showErrorMessage(
                     "",
                     new PopupPositioner(offScreen, popup_));
            }
            
            return ;
         }

         // Move range to beginning of token; we want to place the popup there.
         final String token = completions.token ;

         Rectangle rect = input_.getPositionBounds(
               selection_.getStart().movePosition(-token.length(), true));

         token_ = token ;
         suggestOnAccept_ = completions.suggestOnAccept;
         overrideInsertParens_ = completions.dontInsertParens;

         if (results.length == 1
             && canAutoAccept_
             && StringUtil.isNullOrEmpty(results[0].pkgName))
         {
            onSelection(results[0]);
         }
         else
         {
            if (results.length == 1 && canAutoAccept_)
               applyValue(results[0]);
            
            popup_.showCompletionValues(
                  results,
                  new PopupPositioner(rect, popup_));
         }
      }

      private void onSelection(QualifiedName qname)
      {
         final String value = qname.name ;
         
         if (invalidationToken_.isInvalid())
            return ;
         
         popup_.hide() ;
         popup_.clearHelp(false);
         requester_.flushCache() ;
         helpStrategy_.clearCache();
         
         if (value == null)
         {
            assert false : "Selected comp value is null" ;
            return ;
         }

         applyValue(qname);
         if (suggestOnAccept_ || qname.name.endsWith("/") || qname.name.endsWith(":"))
         {
            Scheduler.get().scheduleDeferred(new ScheduledCommand()
            {
               @Override
               public void execute()
               {
                  beginSuggest(true, true, false);
               }
            });
         }
         
      }
      
      // For input of the form 'something$foo' or 'something@bar', quote the
      // element following '@' if it's a non-syntactic R symbol; otherwise
      // return as is
      private String quoteIfNotSyntacticNameCompletion(String string)
      {
         if (!string.matches("^[a-zA-Z_.][a-zA-Z0-9_.]*$"))
               return "`" + string + "`";
         else
            return string;
      }
      
      private void applyValueRmdOption(final String value)
      {
         input_.setSelection(new InputEditorSelection(
               selection_.getStart().movePosition(-token_.length(), true),
               input_.getSelection().getEnd()));

         input_.replaceSelection(value, true);
         token_ = value;
         selection_ = input_.getSelection();
      }

      private void applyValue(final QualifiedName qualifiedName)
      {
         if (qualifiedName.pkgName == "`chunk-option`")
         {
            applyValueRmdOption(qualifiedName.name);
            return;
         }
         
         boolean insertParen = qualifiedName.type == RCompletionType.FUNCTION;
         
         // Don't insert a paren if there is already a '(' following
         // the cursor
         AceEditor editor = (AceEditor) input_;
         boolean textFollowingCursorHasOpenParen = false;
         if (editor != null)
         {
            TokenCursor cursor = 
                  editor.getSession().getMode().getCodeModel().getTokenCursor();
            cursor.moveToPosition(editor.getCursorPosition());
            if (cursor.moveToNextToken())
               textFollowingCursorHasOpenParen =
               cursor.currentValue() == "(";
         }

         String value = qualifiedName.name;
         String pkgName = qualifiedName.pkgName;
         boolean shouldQuote = qualifiedName.shouldQuote;
         
         if (value == ":=")
            value = quoteIfNotSyntacticNameCompletion(value);
         else if (!value.matches(".*[=:]\\s*$") && 
               !value.matches("^\\s*([`'\"]).*\\1\\s*$") &&
               pkgName != "<file>" &&
               pkgName != "<directory>" &&
               pkgName != "`chunk-option`" &&
               !value.startsWith("@") &&
               !shouldQuote)
            value = quoteIfNotSyntacticNameCompletion(value);

         /* In some cases, applyValue can be called more than once
          * as part of the same completion instance--specifically,
          * if there's only one completion candidate and it is in
          * a package. To make sure that the selection movement
          * logic works the second time, we need to reset the
          * selection.
          */

         // Move range to beginning of token
         input_.setSelection(new InputEditorSelection(
               selection_.getStart().movePosition(-token_.length(), true),
               input_.getSelection().getEnd()));

         if (insertParen && !overrideInsertParens_ && !textFollowingCursorHasOpenParen)
         {
            // Don't replace the selection if the token ends with a ')'
            // (implies an earlier replacement handled this)
            if (token_.endsWith("("))
            {
               input_.setSelection(new InputEditorSelection(
                     input_.getSelection().getEnd(),
                     input_.getSelection().getEnd()));
            }
            else
            {
               input_.replaceSelection(value + "()", true);
               InputEditorSelection newSelection = new InputEditorSelection(
                     input_.getSelection().getEnd().movePosition(-1, true));
               token_ = value + "(";
               selection_ = new InputEditorSelection(
                     input_.getSelection().getStart().movePosition(-2, true),
                     newSelection.getStart());

               input_.setSelection(newSelection);
            }
         }
         else
         {
            if (shouldQuote)
               value = "\"" + value + "\"";
            
            // don't add spaces around equals if requested
            final String kSpaceEquals = " = ";
            if (!uiPrefs_.insertSpacesAroundEquals().getValue() &&
                value.endsWith(kSpaceEquals))
            {
               value = value.substring(0, value.length() - kSpaceEquals.length()) + "=";
            }
               

            input_.replaceSelection(value, true);
            token_ = value;
            selection_ = input_.getSelection();
         }
      }

      private final Invalidation.Token invalidationToken_ ;
      private InputEditorSelection selection_ ;
      private final boolean canAutoAccept_;
      private boolean suggestOnAccept_;
      private boolean overrideInsertParens_;
      
   }
   
   private String getSourceDocumentPath()
   {
      if (rContext_ != null)
         return rContext_.getPath();
      else
         return null;
   }
   
   private GlobalDisplay globalDisplay_;
   private FileTypeRegistry fileTypeRegistry_;
   private EventBus eventBus_;
   private HelpStrategy helpStrategy_;
   private UIPrefs uiPrefs_;

   private final CodeToolsServerOperations server_;
   private final InputEditorDisplay input_ ;
   private final NavigableSourceEditor navigableSourceEditor_;
   private final CompletionPopupDisplay popup_ ;
   private final CompletionRequester requester_ ;
   private final InitCompletionFilter initFilter_ ;
   // Prevents completion popup from being dismissed when you merely
   // click on it to scroll.
   private boolean ignoreNextInputBlur_ = false;
   private String token_ ;
   
   private final DocDisplay docDisplay_;
   private final boolean canAutoPopup_;

   private final Invalidation invalidation_ = new Invalidation();
   private CompletionRequestContext context_ ;
   private final RCompletionContext rContext_;
   private final RnwCompletionContext rnwContext_;
   
   private NativeEvent nativeEvent_;
}
