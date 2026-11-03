import { createClient } from '@connectrpc/connect'
import { createGrpcWebTransport } from '@connectrpc/connect-web'
import { TfmService } from './proto/tfm/v1/tfm_pb'

const transport = createGrpcWebTransport({
  baseUrl: import.meta.env.VITE_BACKEND_URL as string,
})

export const client = createClient(TfmService, transport)
