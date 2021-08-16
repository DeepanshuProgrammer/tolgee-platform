import React, { useRef, useState } from 'react';
import { useContextSelector } from 'use-context-selector';
import { Checkbox, Box } from '@material-ui/core';

import { components } from 'tg.service/apiSchema.generated';
import { Editor } from 'tg.component/editor/Editor';
import { useEditableRow } from './useEditableRow';
import {
  TranslationsContext,
  useTranslationsDispatch,
} from './context/TranslationsContext';
import { stopBubble } from 'tg.fixtures/eventHandler';
import { ScreenshotsPopover } from './Screenshots/ScreenshotsPopover';
import { CellContent, CellPlain, CellControls } from './cell';
import { Tags } from './Tags/Tags';
import { LimitedHeightText } from './LimitedHeightText';

type TagModel = components['schemas']['TagModel'];

type Props = {
  text: string;
  keyId: number;
  keyName: string;
  screenshotCount: number;
  editEnabled: boolean;
  tags: TagModel[] | null;
  width: number;
};

export const CellKey: React.FC<Props> = React.memo(function Cell({
  text,
  keyName,
  keyId,
  screenshotCount,
  editEnabled,
  tags,
  width,
}) {
  const [screenshotsOpen, setScreenshotsOpen] = useState(false);

  const screenshotEl = useRef<HTMLButtonElement | null>(null);

  const {
    isEditing,
    value,
    setValue,
    handleEdit,
    handleEditCancel,
    handleSave,
    autofocus,
  } = useEditableRow({
    keyId,
    keyName,
    defaultVal: keyName,
    language: undefined,
  });

  const isSelected = useContextSelector(TranslationsContext, (c) =>
    c.selection.includes(keyId)
  );

  const isEmpty = keyId < 0;

  const dispatch = useTranslationsDispatch();

  const toggleSelect = () => {
    dispatch({ type: 'TOGGLE_SELECT', payload: keyId });
  };

  return (
    <>
      <CellPlain
        background={isEditing ? '#efefef' : undefined}
        onClick={
          !isEditing && editEnabled ? () => handleEdit(undefined) : undefined
        }
      >
        <CellContent>
          {isEditing ? (
            <Editor
              background="#efefef"
              plaintext
              value={value}
              onChange={(v) => setValue(v as string)}
              onSave={() => handleSave()}
              onCmdSave={() =>
                handleSave(isEmpty ? 'NEW_EMPTY_KEY' : 'EDIT_NEXT')
              }
              onCancel={handleEditCancel}
              autofocus={autofocus}
            />
          ) : (
            <>
              <Box display="flex" alignItems="baseline">
                {editEnabled && (
                  <Box margin={-1} onClick={stopBubble()}>
                    <Checkbox
                      size="small"
                      checked={isSelected}
                      onChange={toggleSelect}
                      data-cy="translations-row-checkbox"
                    />
                  </Box>
                )}
                <Box position="relative">
                  <LimitedHeightText
                    maxLines={3}
                    wrap="break-all"
                    width={width}
                  >
                    {text}
                  </LimitedHeightText>
                </Box>
              </Box>
              <Tags keyId={keyId} tags={tags} />
            </>
          )}
        </CellContent>
        <CellControls
          mode={isEditing ? 'edit' : 'view'}
          onEdit={() => handleEdit(undefined)}
          onCancel={handleEditCancel}
          onSave={handleSave}
          onScreenshots={isEmpty ? undefined : () => setScreenshotsOpen(true)}
          screenshotRef={screenshotEl}
          screenshotsPresent={screenshotCount > 0}
          screenshotsOpen={screenshotsOpen}
          editEnabled={editEnabled}
        />
      </CellPlain>
      {screenshotsOpen && (
        <ScreenshotsPopover
          anchorEl={screenshotEl.current!}
          keyId={keyId}
          onClose={() => {
            setScreenshotsOpen(false);
          }}
        />
      )}
    </>
  );
});
