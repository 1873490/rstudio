/*
 * code_block.ts
 *
 * Copyright (C) 2019-20 by RStudio, PBC
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

import { Node as ProsemirrorNode, Schema } from 'prosemirror-model';
import { textblockTypeInputRule } from 'prosemirror-inputrules';
import { newlineInCode, exitCode } from 'prosemirror-commands';

import { BlockCommand, EditorCommandId, ProsemirrorCommand } from '../api/command';
import { Extension } from '../api/extension';
import { BaseKey } from '../api/basekeys';
import { codeNodeSpec } from '../api/code';
import { PandocOutput, PandocTokenType, PandocExtensions } from '../api/pandoc';
import { pandocAttrSpec, pandocAttrParseDom, pandocAttrToDomAttr } from '../api/pandoc_attr';
import { PandocCapabilities } from '../api/pandoc_capabilities';
import { attrEditCommandFn } from '../behaviors/attr_edit/attr_edit-command';
import { EditorUI, CodeBlockProps } from '../api/ui';
import { EditorState, Transaction } from 'prosemirror-state';
import { EditorView } from 'prosemirror-view';
import { findParentNodeOfType, setTextSelection } from 'prosemirror-utils';
import { canInsertNode } from '../api/node';

const extension = (
  pandocExtensions: PandocExtensions, 
  pandocCapabilities: PandocCapabilities, 
  ui: EditorUI): Extension => 
{
  return {
    nodes: [
      {
        name: 'code_block',

        spec: {
          ...codeNodeSpec(),
          attrs: { ...(pandocExtensions.fenced_code_attributes ? pandocAttrSpec : {}) },
          parseDOM: [
            {
              tag: 'pre',
              preserveWhitespace: 'full',
              getAttrs: (node: Node | string) => {
                if (pandocExtensions.fenced_code_attributes) {
                  const el = node as Element;
                  return pandocAttrParseDom(el, {});
                } else {
                  return {};
                }
              },
            },
          ],
          toDOM(node: ProsemirrorNode) {
            const fontClass = 'pm-fixedwidth-font';
            const attrs = pandocExtensions.fenced_code_attributes
              ? pandocAttrToDomAttr({
                  ...node.attrs,
                  classes: [...node.attrs.classes, fontClass],
                })
              : {
                  class: fontClass,
                };
            return ['pre', attrs, ['code', 0]];
          },
        },

        code_view: {
          lang: (node: ProsemirrorNode) => {
            if (node.attrs.classes && node.attrs.classes.length) {
              return node.attrs.classes[0];
            } else {
              return null;
            }
          },
        },

        attr_edit: () => {
          if (pandocExtensions.fenced_code_attributes) {
            return {
              type: (schema: Schema) => schema.nodes.code_block,
              tags: (node: ProsemirrorNode) => {
                if (node.attrs.classes && node.attrs.classes.length) {
                  const lang = node.attrs.classes[0];
                  if (pandocCapabilities.highlight_languages.includes(lang)) {
                    return [lang];
                  } else {
                    return ['.' + lang];
                  }
                } else {
                  return [];
                }
              },
              editFn: () => codeBlockFormatCommandFn(ui, pandocCapabilities.highlight_languages)
            };
          } else {
            return null;
          }
        },

        pandoc: {
          readers: [
            {
              token: PandocTokenType.CodeBlock,
              code_block: true,
            },
          ],
          writer: (output: PandocOutput, node: ProsemirrorNode) => {
            output.writeToken(PandocTokenType.CodeBlock, () => {
              if (pandocExtensions.fenced_code_attributes) {
                output.writeAttr(node.attrs.id, node.attrs.classes, node.attrs.keyvalue);
              } else {
                output.writeAttr();
              }
              output.write(node.textContent);
            });
          },
        },
      },
    ],

    commands: (schema: Schema) => {
      const commands: ProsemirrorCommand[] = [
        new BlockCommand(
          EditorCommandId.CodeBlock,
          ['Shift-Ctrl-\\'],
          schema.nodes.code_block,
          schema.nodes.paragraph,
          {},
        ),
      ];
      if (pandocExtensions.fenced_code_attributes) {
        commands.push(
          new CodeBlockFormatCommand(ui, pandocCapabilities.highlight_languages)
        );
      }
      return commands;
    },

    baseKeys: () => {
      return [
        { key: BaseKey.Enter, command: newlineInCode },
        { key: BaseKey.ModEnter, command: exitCode },
        { key: BaseKey.ShiftEnter, command: exitCode },
      ];
    },

    inputRules: (schema: Schema) => {
      return [textblockTypeInputRule(/^```$/, schema.nodes.code_block)];
    },
  };
};


class CodeBlockFormatCommand extends ProsemirrorCommand {
  constructor(ui: EditorUI, languages: string[]) {
    super(
      EditorCommandId.CodeBlockFormat,
      [],
      codeBlockFormatCommandFn(ui, languages)
    );
  }
}

function codeBlockFormatCommandFn(ui: EditorUI, languages: string[]) {
  return (state: EditorState, dispatch?: (tr: Transaction) => void, view?: EditorView) => {

    // enable if we are either inside a code block or we can insert a code block
    const schema = state.schema;
    const codeBlock = findParentNodeOfType(schema.nodes.code_block)(state.selection);
    if (!codeBlock && !canInsertNode(state, schema.nodes.code_block)) {
      return false;
    }

    async function asyncEditCodeBlock() {
      if (dispatch) {
        
          // get props to edit
          const codeBlockProps = codeBlock 
            ? { ...codeBlock.node.attrs as CodeBlockProps, lang: '' }
            : { id: '', classes: [], keyvalue: [], lang: '' }; 

          // set lang if the first class is from available languages
          if (codeBlockProps.classes && codeBlockProps.classes.length) {
            const potentialLang = codeBlockProps.classes[0];
            if (languages.includes(potentialLang)) {
              codeBlockProps.lang = potentialLang;
              codeBlockProps.classes = codeBlockProps.classes.slice(1);
            }
          }

          // show dialog
          const result = await ui.dialogs.editCodeBlock(codeBlockProps, languages);
          if (result) {

            // extract lang
            const newProps = { ...result };
            if (newProps.classes && newProps.lang) {
              newProps.classes.unshift(result.lang);
            }

            // edit or insert as appropriate
            const tr = state.tr;
            if (codeBlock) {
              tr.setNodeMarkup(codeBlock.pos, schema.nodes.code_block, newProps);
            } else {
              const prevSel = tr.selection;
              tr.replaceSelectionWith(schema.nodes.code_block.create(newProps));
              setTextSelection(tr.mapping.map(prevSel.from), -1)(tr);
            }
            dispatch(tr);
          }
      }

      if (view) {
        view.focus();
      }
    }

    asyncEditCodeBlock();

    return true;
  };
}



export default extension;
