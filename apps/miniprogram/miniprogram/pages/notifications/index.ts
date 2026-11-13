import {registerPage} from '../../backend/pages'
import {inbox} from '../contact/controller'
registerPage(wx,Page,'notifications')(inbox('notifications',wx))
