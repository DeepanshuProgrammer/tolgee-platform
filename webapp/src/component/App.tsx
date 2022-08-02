import React, { FC, useEffect, useState } from 'react';
import * as Sentry from '@sentry/browser';
import { useSelector } from 'react-redux';
import { Redirect } from 'react-router-dom';
import { Helmet } from 'react-helmet';
import { useTheme } from '@mui/material';
import type API from '@openreplay/tracker';
import { useGlobalContext } from 'tg.globalContext/GlobalContext';
import {
  useConfig,
  useOrganizationUsage,
  usePreferredOrganization,
  useUser,
} from 'tg.globalContext/helpers';
import { GlobalError } from '../error/GlobalError';
import { AppState } from '../store';
import { globalActions } from '../store/global/GlobalActions';
import { errorActions } from '../store/global/ErrorActions';
import { redirectionActions } from '../store/global/RedirectionActions';
import ConfirmationDialog from './common/ConfirmationDialog';
import SnackBar from './common/SnackBar';
import { Chatwoot } from './Chatwoot';
import { useGlobalLoading } from './GlobalLoading';
import { PlanLimitPopover } from './billing/PlanLimitPopover';
import { RootRouter } from './RootRouter';

const Redirection = () => {
  const redirectionState = useSelector((state: AppState) => state.redirection);

  useEffect(() => {
    if (redirectionState.to) {
      redirectionActions.redirectDone.dispatch();
    }
  });

  if (redirectionState.to) {
    return <Redirect to={redirectionState.to} />;
  }

  return null;
};

const MandatoryDataProvider = (props: any) => {
  const config = useConfig();
  const allowPrivate = useSelector(
    (v: AppState) => v.global.security.allowPrivate
  );
  const userData = useUser();
  const isLoading = useGlobalContext((v) => v.isLoading);
  const isFetching = useGlobalContext((v) => v.isFetching);
  const { preferredOrganization } = usePreferredOrganization();
  const [openReplayTracker, setOpenReplayTracker] = useState(
    undefined as undefined | API
  );

  useEffect(() => {
    if (config?.clientSentryDsn) {
      Sentry.init({ dsn: config.clientSentryDsn });
      // eslint-disable-next-line no-console
      console.info('Using Sentry!');
    }
  }, [config?.clientSentryDsn]);

  useEffect(() => {
    const openReplayApiKey = config?.openReplayApiKey;
    if (openReplayApiKey && !window.openReplayTracker) {
      import('@openreplay/tracker').then(({ default: Tracker }) => {
        window.openReplayTracker = new Tracker({
          projectKey: openReplayApiKey,
          __DISABLE_SECURE_MODE:
            process.env.NODE_ENV === 'development' ? true : undefined,
        });
        setOpenReplayTracker(window.openReplayTracker);
        window.openReplayTracker.start();
      });
    }
    setOpenReplayTracker(window.openReplayTracker);
  }, [config?.clientSentryDsn, config?.openReplayApiKey]);

  useEffect(() => {
    if (userData && openReplayTracker) {
      openReplayTracker.setUserID(userData.username);
      setTimeout(() => {
        openReplayTracker?.setUserID(userData.username);
      }, 2000);
    }
  }, [userData, openReplayTracker]);

  useGlobalLoading(isFetching || isLoading);

  if (isLoading || (allowPrivate && !preferredOrganization)) {
    return null;
  } else {
    return props.children;
  }
};

const GlobalConfirmation = () => {
  const state = useSelector(
    (state: AppState) => state.global.confirmationDialog
  );

  const [wasDisplayed, setWasDisplayed] = useState(false);

  const onCancel = () => {
    state?.onCancel?.();
    globalActions.closeConfirmation.dispatch();
  };

  const onConfirm = () => {
    state?.onConfirm?.();
    globalActions.closeConfirmation.dispatch();
  };

  useEffect(() => {
    setWasDisplayed(wasDisplayed || !!state);
  }, [!state]);

  if (!wasDisplayed) {
    return null;
  }

  return (
    <ConfirmationDialog
      open={!!state}
      {...state}
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
};

const GlobalLimitPopover = () => {
  const { planLimitErrors } = useOrganizationUsage();
  const [popoverOpen, setPopoverOpen] = useState(false);
  const handleClose = () => setPopoverOpen(false);

  useEffect(() => {
    if (planLimitErrors === 1) {
      setPopoverOpen(true);
    }
  }, [planLimitErrors]);

  const { preferredOrganization } = usePreferredOrganization();

  return preferredOrganization ? (
    <PlanLimitPopover open={popoverOpen} onClose={handleClose} />
  ) : null;
};

const Head: FC = () => {
  const theme = useTheme();

  return (
    <Helmet>
      <meta name="theme-color" content={theme.palette.navbarBackground.main} />
    </Helmet>
  );
};

export class App extends React.Component {
  componentDidCatch(error: Error, errorInfo: React.ErrorInfo): void {
    errorActions.globalError.dispatch(error as GlobalError);
    throw error;
  }

  render() {
    return (
      <>
        <Head />
        <Redirection />
        <Chatwoot />
        <MandatoryDataProvider>
          <RootRouter />
          <SnackBar />
          <GlobalConfirmation />
          <GlobalLimitPopover />
        </MandatoryDataProvider>
      </>
    );
  }
}
