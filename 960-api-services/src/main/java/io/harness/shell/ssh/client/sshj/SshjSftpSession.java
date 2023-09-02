/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.shell.ssh.client.sshj;

import io.harness.shell.ssh.client.SshSession;

import lombok.Builder;
import lombok.Getter;
import net.schmizz.sshj.sftp.SFTPClient;

@Builder
@Getter
public class SshjSftpSession extends SshSession {
  private SFTPClient sftpClient;
  @Override
  public void close() throws Exception {
    if (sftpClient != null) {
      sftpClient.close();
    }
  }
}
