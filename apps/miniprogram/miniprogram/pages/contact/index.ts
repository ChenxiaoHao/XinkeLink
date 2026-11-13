import {registerPage} from '../../backend/pages'
import {inbox} from '../contact/controller'
registerPage(wx,Page,'contact')(inbox('messages',wx))
